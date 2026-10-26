package io.perms;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Adds the bearer token to every call, and gives unary calls a per-attempt timeout and retries for
 * idempotent reads. It sits on the channel, so the raw stubs get the same behaviour as the SDK's
 * helpers.
 *
 * <p>A deadline set on the stub or call ({@code withDeadlineAfter}) is the overall deadline across
 * retries; each attempt is further bounded by the client's per-attempt timeout.
 */
final class ResilienceInterceptor implements ClientInterceptor {
  static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

  private final String authorization;
  private final long attemptTimeoutNanos;
  private final RetryPolicy retry;
  private final ScheduledExecutorService scheduler;

  ResilienceInterceptor(
      String apiKey,
      Duration attemptTimeout,
      RetryPolicy retry,
      ScheduledExecutorService scheduler) {
    this.authorization = "Bearer " + apiKey;
    this.attemptTimeoutNanos = attemptTimeout.isZero() ? 0 : attemptTimeout.toNanos();
    this.retry = retry;
    this.scheduler = scheduler;
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
    if (method.getType() != MethodDescriptor.MethodType.UNARY) {
      // No public RPC streams today. Pass through with auth, without retries.
      return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
          next.newCall(method, callOptions)) {
        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
          headers.discardAll(AUTHORIZATION);
          headers.put(AUTHORIZATION, authorization);
          super.start(responseListener, headers);
        }
      };
    }
    int maxRetries = RetryPolicy.isIdempotent(method.getFullMethodName()) ? retry.maxRetries() : 0;
    return new RetryingCall<>(method, callOptions, next, maxRetries);
  }

  /**
   * A unary call that buffers the request and replays it on a new underlying call for each attempt.
   * Only the final attempt's outcome reaches the caller's listener.
   */
  private final class RetryingCall<ReqT, RespT> extends ClientCall<ReqT, RespT> {
    private final MethodDescriptor<ReqT, RespT> method;
    private final CallOptions options;
    private final Channel next;
    private final int maxRetries;
    private final Context context = Context.current();
    private final Deadline overall;
    private final Object lock = new Object();

    private Listener<RespT> listener;
    private Metadata headers;
    private ReqT message;
    private int requested;
    private boolean compression;

    // Guarded by lock.
    private int attempt;
    private ClientCall<ReqT, RespT> current;
    private Future<?> pendingRetry;
    private boolean cancelled;
    private boolean closed;

    RetryingCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions options, Channel next, int maxRetries) {
      this.method = method;
      this.options = options;
      this.next = next;
      this.maxRetries = maxRetries;
      Deadline fromContext = context.getDeadline();
      Deadline fromOptions = options.getDeadline();
      this.overall =
          fromContext == null
              ? fromOptions
              : fromOptions == null ? fromContext : fromContext.minimum(fromOptions);
    }

    @Override
    public void start(Listener<RespT> responseListener, Metadata headers) {
      this.listener = responseListener;
      this.headers = headers;
    }

    @Override
    public void request(int numMessages) {
      ClientCall<ReqT, RespT> call;
      synchronized (lock) {
        requested += numMessages;
        call = current;
      }
      if (call != null) {
        call.request(numMessages);
      }
    }

    @Override
    public void sendMessage(ReqT message) {
      this.message = message;
    }

    @Override
    public void setMessageCompression(boolean enabled) {
      this.compression = enabled;
    }

    @Override
    public boolean isReady() {
      synchronized (lock) {
        return current != null && current.isReady();
      }
    }

    @Override
    public void halfClose() {
      startAttempt();
    }

    @Override
    public void cancel(String reason, Throwable cause) {
      ClientCall<ReqT, RespT> call;
      synchronized (lock) {
        if (closed || cancelled) {
          return;
        }
        cancelled = true;
        call = current;
        if (pendingRetry != null) {
          pendingRetry.cancel(false);
          pendingRetry = null;
        }
      }
      if (call != null) {
        // Its onClose delivers CANCELLED to the listener.
        call.cancel(reason, cause);
      } else {
        Status status = Status.CANCELLED;
        if (reason != null) {
          status = status.withDescription(reason);
        }
        if (cause != null) {
          status = status.withCause(cause);
        }
        closeFromOutside(status, new Metadata());
      }
    }

    private void startAttempt() {
      Context previous = context.attach();
      try {
        startAttemptInContext();
      } finally {
        context.detach(previous);
      }
    }

    private void startAttemptInContext() {
      Deadline attemptDeadline =
          attemptTimeoutNanos > 0
              ? Deadline.after(attemptTimeoutNanos, TimeUnit.NANOSECONDS)
              : null;
      boolean perAttemptBound = attemptDeadline != null;
      if (overall != null && (attemptDeadline == null || overall.isBefore(attemptDeadline))) {
        attemptDeadline = overall;
        perAttemptBound = false;
      }
      ClientCall<ReqT, RespT> call = next.newCall(method, options.withDeadline(attemptDeadline));
      int toRequest;
      synchronized (lock) {
        if (cancelled || closed) {
          return;
        }
        current = call;
        pendingRetry = null;
        toRequest = requested;
      }
      Metadata attemptHeaders = new Metadata();
      if (headers != null) {
        attemptHeaders.merge(headers);
      }
      attemptHeaders.discardAll(AUTHORIZATION);
      attemptHeaders.put(AUTHORIZATION, authorization);
      try {
        call.start(new AttemptListener(call, perAttemptBound), attemptHeaders);
        if (compression) {
          call.setMessageCompression(true);
        }
        if (toRequest > 0) {
          call.request(toRequest);
        }
        call.sendMessage(message);
        call.halfClose();
      } catch (RuntimeException e) {
        // E.g. cancel() raced with this attempt's start.
        call.cancel("permio: failed to start attempt", e);
        boolean wasCancelled;
        synchronized (lock) {
          wasCancelled = cancelled;
          if (current == call) {
            current = null;
          }
        }
        closeFromOutside(
            wasCancelled ? Status.CANCELLED.withCause(e) : Status.fromThrowable(e), new Metadata());
      }
    }

    /** Delivers a close that did not come from an attempt's listener, on the call's executor. */
    private void closeFromOutside(Status status, Metadata trailers) {
      Executor executor = options.getExecutor();
      Runnable close =
          () -> {
            synchronized (lock) {
              if (closed) {
                return;
              }
              closed = true;
            }
            listener.onClose(status, trailers);
          };
      if (executor != null) {
        // Blocking stubs wait on this executor; running the close there wakes them.
        executor.execute(close);
      } else {
        close.run();
      }
    }

    private void onAttemptClosed(
        ClientCall<ReqT, RespT> call,
        boolean perAttemptBound,
        Metadata responseHeaders,
        RespT response,
        Status status,
        Metadata trailers) {
      Metadata all = new Metadata();
      if (responseHeaders != null) {
        all.merge(responseHeaders);
      }
      all.merge(trailers);
      synchronized (lock) {
        if (closed || call != current) {
          return;
        }
        boolean overallExpired = overall != null && overall.isExpired();
        if (!status.isOk() && !cancelled && !overallExpired) {
          if (status.getCode() == Status.Code.DEADLINE_EXCEEDED && perAttemptBound) {
            status =
                Status.DEADLINE_EXCEEDED
                    .withDescription(
                        "request timed out after "
                            + TimeUnit.NANOSECONDS.toMillis(attemptTimeoutNanos)
                            + "ms")
                    .withCause(status.asRuntimeException());
          }
          Long delayNanos = retryDelayNanos(status, all);
          if (delayNanos != null) {
            if (overall != null && overall.timeRemaining(TimeUnit.NANOSECONDS) <= delayNanos) {
              status =
                  Status.DEADLINE_EXCEEDED
                      .withDescription("call deadline exceeded before the next retry")
                      .withCause(status.asRuntimeException(all));
            } else {
              attempt++;
              current = null;
              pendingRetry =
                  scheduler.schedule(this::startAttempt, delayNanos, TimeUnit.NANOSECONDS);
              return;
            }
          }
        }
        closed = true;
      }
      if (status.isOk()) {
        if (responseHeaders != null) {
          listener.onHeaders(responseHeaders);
        }
        if (response != null) {
          listener.onMessage(response);
        }
        listener.onClose(status, trailers);
      } else {
        // Headers are folded into the trailers so error mapping can read retry-after.
        listener.onClose(status, all);
      }
    }

    /** The backoff before the next attempt, or null if this error is final. Holds lock. */
    private Long retryDelayNanos(Status status, Metadata metadata) {
      if (attempt >= maxRetries) {
        return null;
      }
      Status.Code code = status.getCode();
      Duration server = RetryDelays.serverRetryDelay(status, metadata);
      boolean retryable =
          code == Status.Code.UNAVAILABLE
              || code == Status.Code.DEADLINE_EXCEEDED
              || (code == Status.Code.RESOURCE_EXHAUSTED && server != null);
      if (!retryable) {
        return null;
      }
      long maxDelay = retry.maxDelay().toNanos();
      if (server != null && server.toNanos() > maxDelay) {
        return null;
      }
      ThreadLocalRandom random = ThreadLocalRandom.current();
      long base = retry.baseDelay().toNanos();
      long ceiling = Math.min(maxDelay, base << Math.min(attempt, 30));
      if (ceiling < 0) {
        ceiling = maxDelay;
      }
      long backoff = ceiling / 2 + (long) (random.nextDouble() * (ceiling / 2.0));
      if (server == null) {
        return backoff;
      }
      // Wait at least as long as the server asked, plus a little jitter so clients limited in the
      // same second don't all retry at once.
      long jitter =
          (long) (random.nextDouble() * Math.min(TimeUnit.MILLISECONDS.toNanos(100), ceiling));
      return Math.max(backoff, server.toNanos() + jitter);
    }

    private final class AttemptListener extends Listener<RespT> {
      private final ClientCall<ReqT, RespT> call;
      private final boolean perAttemptBound;
      private Metadata responseHeaders;
      private RespT response;

      AttemptListener(ClientCall<ReqT, RespT> call, boolean perAttemptBound) {
        this.call = call;
        this.perAttemptBound = perAttemptBound;
      }

      @Override
      public void onHeaders(Metadata headers) {
        this.responseHeaders = headers;
      }

      @Override
      public void onMessage(RespT message) {
        this.response = message;
      }

      @Override
      public void onClose(Status status, Metadata trailers) {
        onAttemptClosed(call, perAttemptBound, responseHeaders, response, status, trailers);
      }
    }
  }
}
