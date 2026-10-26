package io.perms;

import io.perms.proto.permissions.v1.AccessGrant;
import io.perms.proto.permissions.v1.AccessGrantMatch;
import java.util.Optional;

/** One grant (or one role within a grant) that gives a principal access. */
public final class AccessGrantInfo {
  /** How a grant reaches the resource asked about. */
  public enum Match {
    /** The grant is on exactly the resource. */
    EXACT,
    /** The grant is on an ancestor of the resource. */
    ANCESTOR,
    /** The grant is on a URI with {@code _} segments that matches the resource. */
    WILDCARD,
    /** A value this SDK version does not know. */
    UNKNOWN
  }

  /** Where the access in a grant comes from. */
  public enum SourceType {
    /** A role; see {@link #roleId()} and {@link #roleName()}. */
    ROLE,
    /** Directly granted permissions. */
    DIRECT,
    /** A source this SDK version does not know. */
    UNKNOWN
  }

  private final String resource;
  private final boolean inherited;
  private final Match match;
  private final SourceType source;
  private final String roleId;
  private final String roleName;

  private AccessGrantInfo(
      String resource,
      boolean inherited,
      Match match,
      SourceType source,
      String roleId,
      String roleName) {
    this.resource = resource;
    this.inherited = inherited;
    this.match = match;
    this.source = source;
    this.roleId = roleId;
    this.roleName = roleName;
  }

  static AccessGrantInfo of(AccessGrant g) {
    Match match;
    AccessGrantMatch m = g.getMatch();
    switch (m) {
      case ACCESS_GRANT_MATCH_EXACT:
        match = Match.EXACT;
        break;
      case ACCESS_GRANT_MATCH_ANCESTOR:
        match = Match.ANCESTOR;
        break;
      case ACCESS_GRANT_MATCH_WILDCARD:
        match = Match.WILDCARD;
        break;
      default:
        match = Match.UNKNOWN;
    }
    switch (g.getSourceCase()) {
      case ROLE:
        return new AccessGrantInfo(
            g.getResourceUri(),
            g.getInherited(),
            match,
            SourceType.ROLE,
            g.getRole().getId(),
            g.getRole().getName());
      case DIRECT:
        return new AccessGrantInfo(
            g.getResourceUri(), g.getInherited(), match, SourceType.DIRECT, null, null);
      default:
        return new AccessGrantInfo(
            g.getResourceUri(), g.getInherited(), match, SourceType.UNKNOWN, null, null);
    }
  }

  /**
   * The URI the grant was made on: the resource, an ancestor, or a URI with {@code _} segments
   * matching it.
   */
  public String resource() {
    return resource;
  }

  /** True unless the grant was made on exactly the resource. */
  public boolean inherited() {
    return inherited;
  }

  /** How the grant reaches the resource. */
  public Match match() {
    return match;
  }

  /** Through a role, or as directly granted permissions. */
  public SourceType source() {
    return source;
  }

  /** The role's ID, when {@link #source()} is {@link SourceType#ROLE}. */
  public Optional<String> roleId() {
    return Optional.ofNullable(roleId);
  }

  /** The role's name, when {@link #source()} is {@link SourceType#ROLE}. */
  public Optional<String> roleName() {
    return Optional.ofNullable(roleName);
  }

  @Override
  public String toString() {
    return "AccessGrantInfo{resource="
        + resource
        + ", inherited="
        + inherited
        + ", match="
        + match
        + ", source="
        + source
        + (roleId != null ? ", role=" + roleId + "/" + roleName : "")
        + "}";
  }
}
