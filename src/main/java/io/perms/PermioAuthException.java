package io.perms;

import io.grpc.Metadata;
import io.grpc.Status;

/**
 * The API key was missing, unknown, invalidated or otherwise rejected (gRPC {@code
 * UNAUTHENTICATED}).
 */
public class PermioAuthException extends PermioException {
  private static final long serialVersionUID = 1L;

  /** Creates the exception from an API error. */
  public PermioAuthException(String message, Throwable cause, Metadata metadata) {
    super(message, Status.Code.UNAUTHENTICATED, cause, metadata);
  }
}
