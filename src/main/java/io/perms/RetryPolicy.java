package io.perms;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Retry settings for idempotent reads.
 *
 * <p>Reads on the allowlist ({@code Check}, {@code BatchCheck}, {@code WhoAmI}, {@code Get*},
 * {@code List*}, ...; see {@link #isIdempotent(String)}) are retried on {@code UNAVAILABLE} and
 * {@code DEADLINE_EXCEEDED} with jittered exponential backoff. A rate-limited read ({@code
 * RESOURCE_EXHAUSTED}) is retried only when the server says how long to wait and that delay is
 * within {@link #maxDelay()}. Writes, including grant and revoke, are never retried.
 */
public final class RetryPolicy {
  /** Two retries, 100 ms base delay, 2 s maximum delay. */
  public static final RetryPolicy DEFAULT = builder().build();

  /** No retries. */
  public static final RetryPolicy NONE = builder().maxRetries(0).build();

  // Read-only RPCs that are safe to repeat. Anything not listed (every Create, Update, Delete,
  // Grant, Revoke, Invite, Set, Invalidate, Remove...) is sent exactly once.
  //
  // Grant and Revoke converge on the server (a grant is additive, a revoke ignores names the grant
  // lacks), but they stay off this list: after a timeout the first attempt may still land, and
  // repeating it could undo another caller's revoke or grant made in between. The caller decides.
  private static final Set<String> IDEMPOTENT_METHODS =
      Set.of(
          "permio.permissions.v1.PermissionsService/Check",
          "permio.permissions.v1.PermissionsService/GetPermission",
          "permio.permissions.v1.PermissionsService/ListPermissions",
          "permio.permissions.v1.PermissionsService/GetRole",
          "permio.permissions.v1.PermissionsService/ListRoles",
          "permio.permissions.v1.PermissionsService/GetUserPermissionsAndRolesOnResource",
          "permio.permissions.v1.PermissionsService/ListPrincipals",
          "permio.permissions.v1.PermissionsService/GetAllAssignmentsForPrincipal",
          "permio.permissions.v1.PermissionsService/GetTotalRolesAndPermissionsForOrganisation",
          "permio.permissions.v1.PermissionsService/BatchCheck",
          "permio.permissions.v1.PermissionsService/ListAccess",
          "permio.permissions.v1.PermissionsService/ListPrincipalsWithAccess",
          "permio.permissions.v1.PermissionsService/GetGroup",
          "permio.permissions.v1.PermissionsService/ListGroups",
          "permio.permissions.v1.PermissionsService/ListGroupMembers",
          "permio.permissions.v1.PermissionsService/ListGroupsForPrincipal",
          "permio.permissions.v1.PermissionsService/WhoAmI",
          "permio.organisation.v1.OrganisationService/GetOrganisation",
          "permio.organisation.v1.OrganisationService/GetUsersOrganisation",
          "permio.organisation.v1.OrganisationService/ListAPIKeys",
          "permio.organisation.v1.OrganisationService/GetProject",
          "permio.organisation.v1.OrganisationService/GetProjectsForOrganisation",
          "permio.organisation.v1.OrganisationService/GetPrincipalUsageStats",
          "permio.organisation.v1.OrganisationService/GetUsageHistory",
          "permio.organisation.v1.OrganisationService/GetOrganisationDashboard");

  private final int maxRetries;
  private final Duration baseDelay;
  private final Duration maxDelay;

  private RetryPolicy(Builder b) {
    this.maxRetries = b.maxRetries;
    this.baseDelay = b.baseDelay;
    this.maxDelay = b.maxDelay;
  }

  /** A builder starting from the defaults. */
  public static Builder builder() {
    return new Builder();
  }

  /** The default policy with a different number of retries. */
  public static RetryPolicy ofMaxRetries(int maxRetries) {
    return builder().maxRetries(maxRetries).build();
  }

  /** Retries after the first attempt. Default 2. */
  public int maxRetries() {
    return maxRetries;
  }

  /** Delay before the first retry; doubles each time, with jitter. Default 100 ms. */
  public Duration baseDelay() {
    return baseDelay;
  }

  /**
   * Upper bound for a single delay. Default 2 s. A rate-limited read is retried after the server's
   * requested delay only when that delay is within this bound; otherwise {@link
   * PermioRateLimitException} is thrown.
   */
  public Duration maxDelay() {
    return maxDelay;
  }

  /**
   * Whether the SDK retries this RPC. Takes the gRPC full method name, {@code
   * "permio.permissions.v1.PermissionsService/Check"}; the dotted form {@code
   * "permio.permissions.v1.PermissionsService.Check"} is accepted too.
   */
  public static boolean isIdempotent(String fullMethodName) {
    if (fullMethodName == null) {
      return false;
    }
    if (IDEMPOTENT_METHODS.contains(fullMethodName)) {
      return true;
    }
    int dot = fullMethodName.lastIndexOf('.');
    return dot > 0
        && IDEMPOTENT_METHODS.contains(
            fullMethodName.substring(0, dot) + "/" + fullMethodName.substring(dot + 1));
  }

  @Override
  public String toString() {
    return "RetryPolicy{maxRetries="
        + maxRetries
        + ", baseDelay="
        + baseDelay
        + ", maxDelay="
        + maxDelay
        + "}";
  }

  /** Builds a {@link RetryPolicy}. */
  public static final class Builder {
    private int maxRetries = 2;
    private Duration baseDelay = Duration.ofMillis(100);
    private Duration maxDelay = Duration.ofSeconds(2);

    private Builder() {}

    /** Retries after the first attempt; negative values count as 0. */
    public Builder maxRetries(int maxRetries) {
      this.maxRetries = Math.max(0, maxRetries);
      return this;
    }

    /** Delay before the first retry. */
    public Builder baseDelay(Duration baseDelay) {
      this.baseDelay = nonNegative(baseDelay, "baseDelay");
      return this;
    }

    /** Upper bound for a single delay. */
    public Builder maxDelay(Duration maxDelay) {
      this.maxDelay = nonNegative(maxDelay, "maxDelay");
      return this;
    }

    /** Builds the policy. */
    public RetryPolicy build() {
      return new RetryPolicy(this);
    }

    private static Duration nonNegative(Duration d, String what) {
      Objects.requireNonNull(d, what);
      return d.isNegative() ? Duration.ZERO : d;
    }
  }
}
