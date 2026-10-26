package io.perms;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown by {@link Permio#assertAllowed} when the principal does not hold the requested
 * permissions. This is a normal authorization outcome, not an API failure, so {@link #code()} is
 * empty.
 */
public class PermioDeniedException extends PermioException {
  private static final long serialVersionUID = 1L;

  private final String principal;
  private final transient List<MissingPermission> missing;

  /** Creates the exception. */
  public PermioDeniedException(String principal, List<MissingPermission> missing) {
    super(message(principal, missing));
    this.principal = principal;
    this.missing = List.copyOf(missing);
  }

  private static String message(String principal, List<MissingPermission> missing) {
    String summary =
        missing.stream()
            .map(m -> String.join(", ", m.permissions()) + " on " + m.resource())
            .collect(Collectors.joining("; "));
    return "principal \""
        + principal
        + "\" is missing "
        + (summary.isEmpty() ? "the requested permissions" : summary);
  }

  /** The principal that was checked. */
  public String principal() {
    return principal;
  }

  /** What is missing, per resource. */
  public List<MissingPermission> missing() {
    return missing;
  }
}
