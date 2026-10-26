package io.perms;

import io.grpc.Metadata;
import io.grpc.Status;

/** The API could not be reached or is temporarily unavailable (gRPC {@code UNAVAILABLE}). */
public class PermioUnavailableException extends PermioException {
  private static final long serialVersionUID = 1L;

  /** Creates the exception from an API error. */
  public PermioUnavailableException(String message, Throwable cause, Metadata metadata) {
    super(message, Status.Code.UNAVAILABLE, cause, metadata);
  }
}
