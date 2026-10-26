package io.perms;

import io.grpc.Metadata;
import io.grpc.Status;

/** The call did not finish in time (gRPC {@code DEADLINE_EXCEEDED}). */
public class PermioTimeoutException extends PermioException {
  private static final long serialVersionUID = 1L;

  /** Creates the exception from an API error. */
  public PermioTimeoutException(String message, Throwable cause, Metadata metadata) {
    super(message, Status.Code.DEADLINE_EXCEEDED, cause, metadata);
  }
}
