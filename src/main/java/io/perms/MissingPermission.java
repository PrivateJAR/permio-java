package io.perms;

import java.util.List;
import java.util.Objects;

/** Permissions missing on one resource. */
public final class MissingPermission {
  private final String resource;
  private final List<String> permissions;

  /** Creates an entry. */
  public MissingPermission(String resource, List<String> permissions) {
    this.resource = Objects.requireNonNull(resource, "resource");
    this.permissions = List.copyOf(permissions);
  }

  /** The resource URI the permissions are missing on. */
  public String resource() {
    return resource;
  }

  /** The permission names the principal does not hold on {@link #resource()}. */
  public List<String> permissions() {
    return permissions;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof MissingPermission)) {
      return false;
    }
    MissingPermission that = (MissingPermission) o;
    return resource.equals(that.resource) && permissions.equals(that.permissions);
  }

  @Override
  public int hashCode() {
    return Objects.hash(resource, permissions);
  }

  @Override
  public String toString() {
    return "MissingPermission{resource=" + resource + ", permissions=" + permissions + "}";
  }
}
