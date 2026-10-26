package io.perms;

import java.util.List;

/** The result of {@link Permio#checkDetailed}. */
public final class CheckResult {
  private final boolean allowed;
  private final List<MissingPermission> missing;

  CheckResult(boolean allowed, List<MissingPermission> missing) {
    this.allowed = allowed;
    this.missing = List.copyOf(missing);
  }

  /** True when the principal holds every permission on every resource. */
  public boolean allowed() {
    return allowed;
  }

  /** What is missing, per resource, in request order. Empty when allowed. */
  public List<MissingPermission> missing() {
    return missing;
  }

  @Override
  public String toString() {
    return "CheckResult{allowed=" + allowed + ", missing=" + missing + "}";
  }
}
