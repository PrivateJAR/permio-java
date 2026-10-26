package io.perms;

import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** An in-process gRPC server running fake service implementations, and clients for it. */
final class TestServer implements AutoCloseable {
  // Built independently of ApiKeys (Python zlib.crc32), as in permio-js, so the tests do not just
  // check the implementation against itself.
  static final String VALID_KEY = "perms_0123456789ABCDEFGHIJKLMNOPQRSTUV1ggZdL";
  static final String VALID_KEY_2 = "perms_Gm84ZXwbuzUsr46kO1xQ2GwiPWlHJaO32P8mP7";
  static final String JWT_KEY =
      "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ0ZXN0In0.c2lnbmF0dXJlLWJ5dGVz";

  /** Fast retries for tests. */
  static final RetryPolicy FAST =
      RetryPolicy.builder()
          .maxRetries(2)
          .baseDelay(Duration.ofMillis(1))
          .maxDelay(Duration.ofMillis(5))
          .build();

  /** One received call. */
  static final class Received {
    final String method;
    final Metadata headers;

    Received(String method, Metadata headers) {
      this.method = method;
      this.headers = headers;
    }

    String authorization() {
      return headers.get(ResilienceInterceptor.AUTHORIZATION);
    }
  }

  final List<Received> calls = new CopyOnWriteArrayList<>();
  private final Server server;
  private final ManagedChannel channel;

  TestServer(BindableService... services) {
    String name = InProcessServerBuilder.generateName();
    ServerInterceptor recorder =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            calls.add(new Received(call.getMethodDescriptor().getBareMethodName(), headers));
            return next.startCall(call, headers);
          }
        };
    InProcessServerBuilder b = InProcessServerBuilder.forName(name);
    for (BindableService s : services) {
      b.addService(ServerInterceptors.intercept(s, recorder));
    }
    try {
      server = b.build().start();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    channel = InProcessChannelBuilder.forName(name).build();
  }

  Permio.Builder builder() {
    return Permio.builder().apiKey(VALID_KEY).project("my-app").retryPolicy(FAST).channel(channel);
  }

  Permio client() {
    return builder().build();
  }

  Permio client(Consumer<Permio.Builder> configure) {
    Permio.Builder b = builder();
    configure.accept(b);
    return b.build();
  }

  /** The in-process channel the clients use. */
  ManagedChannel channel() {
    return channel;
  }

  long count(String method) {
    return calls.stream().filter(c -> c.method.equals(method)).count();
  }

  @Override
  public void close() {
    channel.shutdownNow();
    server.shutdownNow();
  }

  /** Answers a unary call with {@code f}'s result, or the status exception it throws. */
  static <T> void reply(StreamObserver<T> observer, Supplier<T> f) {
    T value;
    try {
      value = f.get();
    } catch (StatusRuntimeException e) {
      observer.onError(e);
      return;
    }
    observer.onNext(value);
    observer.onCompleted();
  }
}
