package io.perms;

import io.grpc.Metadata;
import io.grpc.Status;

/**
 * The request was rejected as malformed (gRPC {@code INVALID_ARGUMENT}), or failed the SDK's own
 * input checks (then {@link #code()} is empty).
 */
public class PermioValidationException extends PermioException {
  private static final long serialVersionUID = 1L;

  /** An input error found by the SDK before any request was sent. */
  public PermioValidationException(String message) {
    super(message);
  }

  /** Creates the exception from an API error. */
  public PermioValidationException(
      String message, Status.Code code, Throwable cause, Metadata metadata) {
    super(message, code, cause, metadata);
  }
}
