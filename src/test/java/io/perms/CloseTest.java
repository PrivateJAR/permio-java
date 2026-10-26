package io.perms;

import static io.perms.TestServer.reply;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptors;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.perms.proto.permissions.v1.CheckPermissionRequest;
import io.perms.proto.permissions.v1.CheckPermissionResponse;
import io.perms.proto.permissions.v1.PermissionsServiceGrpc;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** close() must never leave a call hanging. Every test has a timeout so a regression fails. */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class CloseTest {
  /** Retries that wait 15-30 s: long enough that only close() can end the wait in time. */
  private static final RetryPolicy SLOW =
      RetryPolicy.builder()
          .maxRetries(2)
          .baseDelay(Duration.ofSeconds(30))
          .maxDelay(Duration.ofSeconds(30))
          .build();

  private TestServer server;
  private Permio permio;

  @AfterEach
  void tearDown() {
    if (permio != null) {
      permio.close();
    }
    if (server != null) {
      server.close();
    }
  }

  /** Check always fails UNAVAILABLE, counting calls. */
  private static final class Unavailable extends PermissionsServiceGrpc.PermissionsServiceImplBase {
    final AtomicInteger calls = new AtomicInteger();

    @Override
    public void check(CheckPermissionRequest r, StreamObserver<CheckPermissionResponse> o) {
      calls.incrementAndGet();
      o.onError(Status.UNAVAILABLE.withDescription("down").asRuntimeException());
    }
  }

  private static final class Allowed extends PermissionsServiceGrpc.PermissionsServiceImplBase {
    @Override
    public void check(CheckPermissionRequest r, StreamObserver<CheckPermissionResponse> o) {
      reply(o, () -> CheckPermissionResponse.newBuilder().setPassed(true).build());
    }
  }

  private static void awaitCalls(AtomicInteger calls, int n) throws InterruptedException {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (calls.get() < n) {
      assertThat(System.nanoTime()).as("waiting for %d calls", n).isLessThan(until);
      Thread.sleep(5);
    }
    // Let the failed attempt reach the retry scheduler.
    Thread.sleep(100);
  }

  @Test
  void closeDuringBackoffFailsTheWaitingCall() throws Exception {
    Unavailable svc = new Unavailable();
    server = new TestServer(svc);
    permio = server.client(b -> b.retryPolicy(SLOW));
    CompletableFuture<Boolean> blocking = new CompletableFuture<>();
    Thread t =
        new Thread(
            () -> {
              try {
                blocking.complete(permio.check("alice", "docs.read", "/docs/1"));
              } catch (Throwable e) {
                blocking.completeExceptionally(e);
              }
            });
    t.setDaemon(true);
    t.start();
    CompletableFuture<Boolean> async = permio.checkAsync("bob", "docs.read", "/docs/1");
    awaitCalls(svc.calls, 2);

    permio.close();

    for (CompletableFuture<Boolean> f : java.util.List.of(blocking, async)) {
      assertThatThrownBy(() -> f.get(5, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .cause()
          .isInstanceOf(PermioUnavailableException.class)
          .hasMessageContaining("closed");
    }
    assertThat(svc.calls.get()).isEqualTo(2);
  }

  @Test
  void callsAfterCloseFailFast() {
    server = new TestServer(new Allowed());
    permio = server.client(b -> b.retryPolicy(SLOW));
    Permio child = permio.withProject("other").withCallTimeout(Duration.ofMinutes(5));
    permio.close();

    assertThatThrownBy(() -> permio.check("alice", "docs.read", "/docs/1"))
        .isInstanceOf(PermioUnavailableException.class)
        .hasMessageContaining("closed");
    assertThatThrownBy(() -> child.check("alice", "docs.read", "/docs/1"))
        .isInstanceOf(PermioUnavailableException.class);
    assertThatThrownBy(
            () -> permio.raw().permissions().check(CheckPermissionRequest.newBuilder().build()))
        .satisfies(
            e ->
                assertThat(PermioException.from(e)).isInstanceOf(PermioUnavailableException.class));
    assertThatThrownBy(
            () -> child.checkAsync("alice", "docs.read", "/docs/1").get(5, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(PermioUnavailableException.class);
    assertThatThrownBy(() -> child.groups().getAsync("editors").get(5, TimeUnit.SECONDS))
        .hasCauseInstanceOf(PermioUnavailableException.class);
    assertThat(server.count("Check")).isZero();
  }

  @Test
  void callAfterCloseOnAnOwnedChannelDoesNotHang() {
    // Nothing listens on port 1; the owned channel is shut down by close().
    permio =
        Permio.builder()
            .apiKey(TestServer.VALID_KEY)
            .baseUrl("http://127.0.0.1:1")
            .project("p")
            .retryPolicy(SLOW)
            .build();
    permio.close();
    assertThatThrownBy(() -> permio.check("alice", "docs.read", "/docs/1"))
        .isInstanceOf(PermioUnavailableException.class);
  }

  @Test
  void closingAViewDoesNothing() {
    server = new TestServer(new Allowed());
    permio = server.client();
    permio.withProject("other").close();
    assertThat(permio.check("alice", "docs.read", "/docs/1")).isTrue();
  }

  @Test
  void callerSuppliedChannelIsNotClosed() {
    server = new TestServer(new Allowed());
    permio = server.client();
    permio.close();
    assertThat(server.channel().isShutdown()).isFalse();
    // The channel still works for its owner.
    Permio other = server.client();
    try {
      assertThat(other.check("alice", "docs.read", "/docs/1")).isTrue();
    } finally {
      other.close();
    }
  }

  @Test
  void aRetryTheSchedulerRejectsEndsWithTheLastError() throws Exception {
    // The scheduler is already stopped but the interceptor was not told: scheduling the retry
    // throws RejectedExecutionException, and the call must still end.
    Unavailable svc = new Unavailable();
    server = new TestServer(svc);
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    scheduler.shutdownNow();
    Channel channel =
        ClientInterceptors.intercept(
            server.channel(),
            new ResilienceInterceptor(
                TestServer.VALID_KEY, Duration.ofSeconds(5), SLOW, scheduler));
    PermissionsServiceGrpc.PermissionsServiceBlockingStub stub =
        PermissionsServiceGrpc.newBlockingStub(channel);
    assertThatThrownBy(() -> stub.check(CheckPermissionRequest.newBuilder().build()))
        .satisfies(
            e -> {
              PermioException p = PermioException.from(e);
              assertThat(p).isInstanceOf(PermioUnavailableException.class).hasMessage("down");
            });
    assertThat(svc.calls.get()).isEqualTo(1);
  }

  @Test
  void streamingCallsAfterShutdownFail() {
    ResilienceInterceptor interceptor =
        new ResilienceInterceptor(
            TestServer.VALID_KEY, Duration.ofSeconds(1), SLOW, new ScheduledThreadPoolExecutor(1));
    interceptor.shutdown();
    MethodDescriptor<CheckPermissionRequest, CheckPermissionResponse> streaming =
        PermissionsServiceGrpc.getCheckMethod().toBuilder()
            .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
            .build();
    server = new TestServer(new Allowed());
    ClientCall<CheckPermissionRequest, CheckPermissionResponse> call =
        interceptor.interceptCall(streaming, CallOptions.DEFAULT, server.channel());
    CompletableFuture<Status> closed = new CompletableFuture<>();
    call.start(
        new ClientCall.Listener<CheckPermissionResponse>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            closed.complete(status);
          }
        },
        new Metadata());
    assertThat(closed.join().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
  }
}
