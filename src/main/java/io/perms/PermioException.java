package io.perms;

import io.grpc.Metadata;
import io.grpc.Status;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Base class of every exception thrown by the SDK. It is unchecked.
 *
 * <p>{@link #code()} is the status code (an SDK {@link ErrorCode}; {@link #grpcCode()} gives the
 * gRPC one) when the error came from (or maps to) an API status, and {@link #metadata()} holds the
 * response headers and trailers sent with it. The subclasses mirror the gRPC codes a caller usually
 * handles:
 *
 * <ul>
 *   <li>{@link PermioConfigException}: the client is misconfigured (no gRPC code)
 *   <li>{@link PermioAuthException}: {@code UNAUTHENTICATED}
 *   <li>{@link PermioPermissionException}: {@code PERMISSION_DENIED}
 *   <li>{@link PermioValidationException}: {@code INVALID_ARGUMENT}, or the SDK's own input checks
 *   <li>{@link PermioNotFoundException}: {@code NOT_FOUND}
 *   <li>{@link PermioUnavailableException}: {@code UNAVAILABLE}
 *   <li>{@link PermioTimeoutException}: {@code DEADLINE_EXCEEDED}
 *   <li>{@link PermioRateLimitException}: {@code RESOURCE_EXHAUSTED}
 *   <li>{@link PermioDeniedException}: thrown by {@link Permio#assertAllowed}; not an API error
 * </ul>
 *
 * <p>Errors from the raw gRPC stubs ({@link Permio#raw()}) are plain {@code
 * StatusRuntimeException}s; convert them with {@link #from(Throwable)}.
 */
public class PermioException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final transient Status.Code code;
  private final transient Metadata metadata;

  /** An error without a gRPC status. */
  public PermioException(String message) {
    this(message, null, null, null);
  }

  /** An error with an optional gRPC status code, cause and response metadata. */
  public PermioException(String message, Status.Code code, Throwable cause, Metadata metadata) {
    super(message, cause);
    this.code = code;
    this.metadata = metadata != null ? metadata : new Metadata();
  }

  /** The status code, when the error came from (or maps to) an API status. */
  public Optional<ErrorCode> code() {
    return grpcCode().map(ErrorCode::of);
  }

  /** The gRPC status code, when the error came from (or maps to) an API status. */
  public Optional<Status.Code> grpcCode() {
    return Optional.ofNullable(code);
  }

  /** Lower-case code name, e.g. {@code "permission_denied"}, if there is a code. */
  public Optional<String> codeName() {
    return grpcCode().map(c -> c.name().toLowerCase(Locale.ROOT));
  }

  /** Response headers and trailers sent with the error. Empty when there were none. */
  public Metadata metadata() {
    return metadata;
  }

  /**
   * Converts any throwable into the matching {@code PermioException} subclass. A {@code
   * PermioException} is returned as is; {@link ExecutionException} and {@link CompletionException}
   * are unwrapped; gRPC status exceptions are mapped by code; anything else becomes a base {@code
   * PermioException} with code {@code UNKNOWN}.
   */
  public static PermioException from(Throwable error) {
    Throwable t = error;
    while ((t instanceof ExecutionException || t instanceof CompletionException)
        && t.getCause() != null) {
      t = t.getCause();
    }
    if (t instanceof PermioException) {
      return (PermioException) t;
    }
    Status status = Status.fromThrowable(t);
    Metadata trailers = Status.trailersFromThrowable(t);
    return fromStatus(status, trailers, t);
  }

  static PermioException fromStatus(Status status, Metadata trailers, Throwable cause) {
    String message = status.getDescription();
    if (message == null || message.isEmpty()) {
      message =
          status.getCause() != null && status.getCause().getMessage() != null
              ? status.getCause().getMessage()
              : status.getCode().name();
    }
    Status.Code code = status.getCode();
    switch (code) {
      case UNAUTHENTICATED:
        return new PermioAuthException(message, cause, trailers);
      case PERMISSION_DENIED:
        return new PermioPermissionException(
            message, cause, trailers, parseMissingPermissions(message));
      case INVALID_ARGUMENT:
        return new PermioValidationException(message, code, cause, trailers);
      case NOT_FOUND:
        return new PermioNotFoundException(message, cause, trailers);
      case UNAVAILABLE:
        return new PermioUnavailableException(message, cause, trailers);
      case DEADLINE_EXCEEDED:
        return new PermioTimeoutException(message, cause, trailers);
      case RESOURCE_EXHAUSTED:
        Duration retryAfter =
            trailers == null ? null : RetryDelays.serverRetryDelay(status, trailers);
        return new PermioRateLimitException(message, cause, trailers, retryAfter);
      default:
        return new PermioException(message, code, cause, trailers);
    }
  }

  // The permissions service formats PermissionDenied as Go's %+v of its missing list:
  //   missing permissions: [{ResourceURI:/organisation/o/project/p/doc
  // MissingPermissions:[permission.check]} ...]
  private static final Pattern MISSING_ENTRY =
      Pattern.compile("\\{ResourceURI:(\\S*) MissingPermissions:\\[([^\\]]*)\\]\\}");

  /** Extracts the missing-permission list from a PermissionDenied message, if it has one. */
  public static List<MissingPermission> parseMissingPermissions(String message) {
    if (message == null) {
      return Collections.emptyList();
    }
    List<MissingPermission> out = new ArrayList<>();
    Matcher m = MISSING_ENTRY.matcher(message);
    while (m.find()) {
      List<String> permissions = new ArrayList<>();
      for (String p : m.group(2).split(" ")) {
        if (!p.isEmpty()) {
          permissions.add(p);
        }
      }
      out.add(new MissingPermission(m.group(1), permissions));
    }
    return Collections.unmodifiableList(out);
  }
}
