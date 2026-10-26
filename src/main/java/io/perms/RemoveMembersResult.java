package io.perms;

import java.util.List;

/** The result of {@link Groups#removeMembers}. */
public final class RemoveMembersResult {
  private final List<String> removed;
  private final List<String> notMembers;

  RemoveMembersResult(List<String> removed, List<String> notMembers) {
    this.removed = List.copyOf(removed);
    this.notMembers = List.copyOf(notMembers);
  }

  /** Principals that were removed, in request order. */
  public List<String> removed() {
    return removed;
  }

  /** Principals that were not members, in request order. */
  public List<String> notMembers() {
    return notMembers;
  }

  @Override
  public String toString() {
    return "RemoveMembersResult{removed=" + removed + ", notMembers=" + notMembers + "}";
  }
}
