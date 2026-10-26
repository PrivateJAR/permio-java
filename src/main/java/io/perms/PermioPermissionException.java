package io.perms;

import io.grpc.Metadata;
import io.grpc.Status;
import java.util.Collections;
import java.util.List;

/**
 * The API key is valid but lacks the rights for this call (gRPC {@code PERMISSION_DENIED}).
 *
 * <p>For {@code Check}, the key needs {@code permission.check} on the checked resources and on the
 * principal. When the API lists what is missing, it is parsed into {@link #missing()}. Those
 * resource URIs are the fully qualified internal ones ({@code /organisation/{org}/project/...}),
 * not the URIs you sent.
 *
 * <p>This is about the <em>caller's</em> rights. A principal failing a check is not an error:
 * {@link Permio#check} returns {@code false} and {@link Permio#assertAllowed} throws {@link
 * PermioDeniedException}.
 */
public class PermioPermissionException extends PermioException {
  private static final long serialVersionUID = 1L;

  private final transient List<MissingPermission> missing;

  /** Creates the exception from an API error. */
  public PermioPermissionException(
      String message, Throwable cause, Metadata metadata, List<MissingPermission> missing) {
    super(message, Status.Code.PERMISSION_DENIED, cause, metadata);
    this.missing = missing == null ? Collections.emptyList() : List.copyOf(missing);
  }

  /** What the API key is missing, when the API said. */
  public List<MissingPermission> missing() {
    return missing;
  }
}
