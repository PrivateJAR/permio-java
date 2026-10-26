package io.perms;

import java.util.List;
import java.util.Optional;

/** One principal with access to a resource, returned by {@link Permio#whoHasAccess}. */
public final class AccessEntry {
  private final String principal;
  private final boolean group;
  private final String viaGroup;
  private final List<AccessGrantInfo> via;

  AccessEntry(String principal, boolean group, String viaGroup, List<AccessGrantInfo> via) {
    this.principal = principal;
    this.group = group;
    this.viaGroup = viaGroup;
    this.via = List.copyOf(via);
  }

  /** The principal ID; {@code group:<slug>} for a group. */
  public String principal() {
    return principal;
  }

  /** True when {@link #principal()} is a group. */
  public boolean isGroup() {
    return group;
  }

  /**
   * The slug of the group through which the principal has the grants in {@link #via()}, for entries
   * added by {@code expandGroups}. Empty for grants made to the principal itself.
   */
  public Optional<String> viaGroup() {
    return Optional.ofNullable(viaGroup);
  }

  /** The grants that give the access, most specific first. */
  public List<AccessGrantInfo> via() {
    return via;
  }

  @Override
  public String toString() {
    return "AccessEntry{principal="
        + principal
        + ", isGroup="
        + group
        + ", viaGroup="
        + viaGroup
        + ", via="
        + via
        + "}";
  }
}
