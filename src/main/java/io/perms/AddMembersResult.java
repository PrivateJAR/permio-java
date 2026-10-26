package io.perms;

import java.util.List;

/** The result of {@link Groups#addMembers}. */
public final class AddMembersResult {
  private final List<String> added;
  private final List<String> existing;

  AddMembersResult(List<String> added, List<String> existing) {
    this.added = List.copyOf(added);
    this.existing = List.copyOf(existing);
  }

  /** Principals that were added, in request order. */
  public List<String> added() {
    return added;
  }

  /** Principals that were already members, in request order. */
  public List<String> existing() {
    return existing;
  }

  @Override
  public String toString() {
    return "AddMembersResult{added=" + added + ", existing=" + existing + "}";
  }
}
