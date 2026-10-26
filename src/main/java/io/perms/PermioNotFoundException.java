package io.perms;

import io.grpc.Metadata;
import io.grpc.Status;

/** The requested entity does not exist (gRPC {@code NOT_FOUND}). */
public class PermioNotFoundException extends PermioException {
  private static final long serialVersionUID = 1L;

  /** Creates the exception from an API error. */
  public PermioNotFoundException(String message, Throwable cause, Metadata metadata) {
    super(message, Status.Code.NOT_FOUND, cause, metadata);
  }
}
