package io.perms;

import java.util.Optional;

/** The caller, as returned by {@link Permio#whoAmI()}. */
public final class WhoAmI {
  /** The kind of credential the client is using. */
  public enum PrincipalKind {
    /** A dashboard user. */
    USER,
    /** An API key. */
    API_KEY,
    /** A service account. */
    SERVICE_ACCOUNT,
    /** A kind this SDK version does not know. */
    UNKNOWN
  }

  /** Access level of an API key on its project. */
  public enum ApiKeyAccess {
    /** Checks and reads. */
    CHECK,
    /** Also writes, grant and revoke. */
    FULL,
    /** Its rights were changed by hand. */
    UNKNOWN
  }

  private final String principalId;
  private final String organisationId;
  private final PrincipalKind kind;
  private final String project;
  private final ApiKeyAccess apiKeyAccess;
  private final String email;

  WhoAmI(
      String principalId,
      String organisationId,
      PrincipalKind kind,
      String project,
      ApiKeyAccess apiKeyAccess,
      String email) {
    this.principalId = principalId;
    this.organisationId = organisationId;
    this.kind = kind;
    this.project = project;
    this.apiKeyAccess = apiKeyAccess;
    this.email = email;
  }

  /** The caller's principal ID; for an API key, the key's ID. */
  public String principalId() {
    return principalId;
  }

  /** The caller's organisation. Empty for a user who has not joined one yet. */
  public String organisationId() {
    return organisationId;
  }

  /** What kind of credential the client is using. */
  public PrincipalKind kind() {
    return kind;
  }

  /** The project an API key belongs to; it can only be used there. Empty for users. */
  public Optional<String> project() {
    return Optional.ofNullable(project);
  }

  /** For an API key, its access level. Empty for other credentials. */
  public Optional<ApiKeyAccess> apiKeyAccess() {
    return Optional.ofNullable(apiKeyAccess);
  }

  /** The user's email address, when the caller is a user and has one. */
  public Optional<String> email() {
    return Optional.ofNullable(email);
  }

  @Override
  public String toString() {
    return "WhoAmI{principalId="
        + principalId
        + ", organisationId="
        + organisationId
        + ", kind="
        + kind
        + ", project="
        + project
        + ", apiKeyAccess="
        + apiKeyAccess
        + "}";
  }
}
