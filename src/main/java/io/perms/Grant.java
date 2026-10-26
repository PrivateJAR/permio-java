package io.perms;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * What to grant or revoke: permissions and roles for a principal on a resource. Immutable; each
 * method returns a copy.
 *
 * <pre>{@code
 * permio.grant(Grant.to("alice").on("/docs").permissions("docs.read").roles("editor"));
 * permio.grant(Grant.to(Groups.principal("editors")).on("/docs").permissions("docs.write"));
 * }</pre>
 */
public final class Grant {
  private final String principal;
  private final String resource;
  private final List<String> permissions;
  private final List<String> roles;

  private Grant(String principal, String resource, List<String> permissions, List<String> roles) {
    this.principal = principal;
    this.resource = resource;
    this.permissions = permissions;
    this.roles = roles;
  }

  /**
   * Starts a grant for {@code principal}: one of your IDs, or {@code Groups.principal(slug)} for a
   * group.
   */
  public static Grant to(String principal) {
    return new Grant(principal, null, List.of(), List.of());
  }

  /**
   * The resource URI of the grant, e.g. {@code Resources.resource("docs", id)}. A grant also covers
   * every URI below it; a revoke applies to exactly this URI.
   */
  public Grant on(String resource) {
    return new Grant(principal, resource, permissions, roles);
  }

  /** Adds permission names. */
  public Grant permissions(String... permissions) {
    return permissions(Arrays.asList(permissions));
  }

  /** Adds permission names. */
  public Grant permissions(Collection<String> permissions) {
    return new Grant(principal, resource, concat(this.permissions, permissions), roles);
  }

  /** Adds role IDs (see {@link Permio#listRoles()}). */
  public Grant roles(String... roles) {
    return roles(Arrays.asList(roles));
  }

  /** Adds role IDs (see {@link Permio#listRoles()}). */
  public Grant roles(Collection<String> roles) {
    return new Grant(principal, resource, permissions, concat(this.roles, roles));
  }

  /** The principal. */
  public String principal() {
    return principal;
  }

  /** The resource URI. */
  public String resource() {
    return resource;
  }

  /** The permission names. */
  public List<String> permissions() {
    return permissions;
  }

  /** The role IDs. */
  public List<String> roles() {
    return roles;
  }

  private static List<String> concat(List<String> a, Collection<String> b) {
    Objects.requireNonNull(b, "values");
    List<String> out = new ArrayList<>(a);
    out.addAll(b);
    return java.util.Collections.unmodifiableList(out);
  }

  @Override
  public String toString() {
    return "Grant{principal="
        + principal
        + ", resource="
        + resource
        + ", permissions="
        + permissions
        + ", roles="
        + roles
        + "}";
  }
}
