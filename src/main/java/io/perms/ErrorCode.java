package io.perms;

import io.grpc.Status;

/**
 * The status code of a {@link PermioException}, owned by the SDK so callers need not depend on gRPC
 * types. The values mirror the gRPC status codes (without {@code OK}); {@link
 * PermioException#grpcCode()} gives the gRPC code itself.
 */
public enum ErrorCode {
  CANCELLED,
  UNKNOWN,
  INVALID_ARGUMENT,
  DEADLINE_EXCEEDED,
  NOT_FOUND,
  ALREADY_EXISTS,
  PERMISSION_DENIED,
  RESOURCE_EXHAUSTED,
  FAILED_PRECONDITION,
  ABORTED,
  OUT_OF_RANGE,
  UNIMPLEMENTED,
  INTERNAL,
  UNAVAILABLE,
  DATA_LOSS,
  UNAUTHENTICATED;

  /** The SDK code for a gRPC code; {@code OK} (never an error) maps to {@link #UNKNOWN}. */
  public static ErrorCode of(Status.Code code) {
    if (code == null || code == Status.Code.OK) {
      return UNKNOWN;
    }
    return valueOf(code.name());
  }
}
