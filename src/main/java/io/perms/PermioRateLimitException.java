package io.perms;

import io.grpc.Metadata;
import io.grpc.Status;
import java.time.Duration;
import java.util.Optional;

/**
 * The organisation's rate limit is used up (gRPC {@code RESOURCE_EXHAUSTED}). Idempotent reads are
 * retried automatically after the server's requested delay when it is within {@link
 * RetryPolicy#maxDelay()}; otherwise this is thrown with {@link #retryAfter()} set to that delay.
 */
public class PermioRateLimitException extends PermioException {
  private static final long serialVersionUID = 1L;

  private final Duration retryAfter;

  /** Creates the exception from an API error. */
  public PermioRateLimitException(
      String message, Throwable cause, Metadata metadata, Duration retryAfter) {
    super(message, Status.Code.RESOURCE_EXHAUSTED, cause, metadata);
    this.retryAfter = retryAfter;
  }

  /**
   * How long the server asked the client to wait before retrying (a {@code google.rpc.RetryInfo}
   * detail, else a {@code retry-after} header), if it said.
   */
  public Optional<Duration> retryAfter() {
    return Optional.ofNullable(retryAfter);
  }
}
