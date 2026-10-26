package io.perms;

import java.util.List;

/** One resource's result from {@link Permio#batchCheckDetailed}. */
public final class BatchCheckEntry {
  private final String resource;
  private final boolean allowed;
  private final List<String> missing;

  BatchCheckEntry(String resource, boolean allowed, List<String> missing) {
    this.resource = resource;
    this.allowed = allowed;
    this.missing = List.copyOf(missing);
  }

  /** The resource URI. */
  public String resource() {
    return resource;
  }

  /** True when the principal holds every requested permission on {@link #resource()}. */
  public boolean allowed() {
    return allowed;
  }

  /** The requested permissions the principal lacks on the resource, in request order. */
  public List<String> missing() {
    return missing;
  }

  @Override
  public String toString() {
    return "BatchCheckEntry{resource="
        + resource
        + ", allowed="
        + allowed
        + ", missing="
        + missing
        + "}";
  }
}
