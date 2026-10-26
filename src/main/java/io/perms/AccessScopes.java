package io.perms;

import io.perms.proto.permissions.v1.AccessScopeKind;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The answer to "where may this principal use this permission under {@code under}?", returned by
 * {@link Permio#listAccess}. Every page has already been fetched.
 *
 * <pre>{@code
 * AccessScopes access = permio.listAccess(userId, "docs.read", "/docs");
 * docs.stream().filter(d -> access.allows(Resources.resource("docs", d.id())));
 * }</pre>
 *
 * <p>The scopes describe resources at or below {@code under}. A URI outside {@code under} was not
 * asked about, so {@link #allows} returning {@code false} for it means "unknown", not "denied". A
 * pattern scope may also match some URIs outside {@code under} (its {@code _} segments can sit in
 * the part that matches {@code under}); those really are allowed.
 */
public final class AccessScopes {
  /** The {@code _} segment: in a pattern scope it matches exactly one segment. */
  public static final String WILDCARD_SEGMENT = "_";

  /** Which resources a scope's URI stands for. */
  public enum Kind {
    /** The URI itself and everything below it. */
    SUBTREE,
    /**
     * Every URI whose leading segments match the URI, where each {@code _} segment matches exactly
     * one segment, and everything below those.
     */
    PATTERN,
    /** Exactly the URI. Reserved by the API; not returned today. */
    EXACT,
    /** A kind this SDK version does not know. It never matches. */
    UNKNOWN;

    static Kind of(AccessScopeKind kind) {
      switch (kind) {
        case ACCESS_SCOPE_KIND_SUBTREE:
          return SUBTREE;
        case ACCESS_SCOPE_KIND_PATTERN:
          return PATTERN;
        case ACCESS_SCOPE_KIND_EXACT:
          return EXACT;
        default:
          return UNKNOWN;
      }
    }
  }

  /** One place where a principal holds a permission. */
  public static final class Scope {
    private final String uri;
    private final Kind kind;

    /** Creates a scope. */
    public Scope(String uri, Kind kind) {
      this.uri = Objects.requireNonNull(uri, "uri");
      this.kind = Objects.requireNonNull(kind, "kind");
    }

    /** Resource URI (subtree, exact) or URI pattern with {@code _} segments (pattern). */
    public String uri() {
      return uri;
    }

    /** What {@link #uri()} stands for. */
    public Kind kind() {
      return kind;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Scope && ((Scope) o).uri.equals(uri) && ((Scope) o).kind == kind;
    }

    @Override
    public int hashCode() {
      return Objects.hash(uri, kind);
    }

    @Override
    public String toString() {
      return kind + " " + uri;
    }
  }

  private final String under;
  private final boolean coversAll;
  private final List<Scope> scopes;

  /** Creates the result; used by the client and handy in tests. */
  public AccessScopes(String under, boolean coversAll, List<Scope> scopes) {
    this.under = under;
    this.coversAll = coversAll;
    this.scopes = List.copyOf(scopes);
  }

  /** The {@code under} URI that was asked about. */
  public String under() {
    return under;
  }

  /**
   * True when the principal holds the permission on {@code under} itself, and so on everything
   * below it. {@link #scopes()} is then a single subtree scope for {@code under}. Check this first:
   * when it is true, a list needs no filter at all.
   */
  public boolean coversAll() {
    return coversAll;
  }

  /** Every scope, ordered by URI as the API returned them. */
  public List<Scope> scopes() {
    return scopes;
  }

  /** True when the principal has no access anywhere under {@code under}. */
  public boolean isEmpty() {
    return !coversAll && scopes.stream().allMatch(s -> s.kind == Kind.UNKNOWN);
  }

  /**
   * Whether the principal holds the permission on {@code uri}, according to these scopes. Matching
   * is per segment: a subtree scope {@code /a/b} allows {@code /a/b} and {@code /a/b/c} but not
   * {@code /a/bc}; a pattern scope {@code /a/_/c} allows {@code /a/x/c} and {@code /a/x/c/d} but
   * not {@code /a/x} or {@code /a/x/y}. Malformed URIs are never allowed.
   */
  public boolean allows(String uri) {
    String[] target = segments(uri);
    if (target == null) {
      return false;
    }
    for (Scope s : scopes) {
      if (matches(s, target)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The subtree scopes' URIs, for filtering a database by path prefix. A URI {@code p} here stands
   * for {@code p} itself and everything below it, so match {@code path = p} or {@code path}
   * starting with {@code p + "/"}; a bare prefix match would also match {@code /docs/12} for {@code
   * /docs/1}. Use a literal starts-with test, e.g. PostgreSQL {@code starts_with(path, p || '/')}
   * or {@code left(path, length(p) + 1) = p || '/'}, not {@code LIKE}: {@code _} and {@code %} are
   * {@code LIKE} wildcards, and {@code _} is common in URIs (and is Permio's own wildcard segment).
   * If you must use {@code LIKE}, escape backslash, {@code %} and {@code _} in {@code p} first.
   * Prefixes already covered by a shorter one are dropped.
   *
   * <p>Pattern scopes cannot be expressed as a prefix and are not included: see {@link #patterns()}
   * and {@link #toRegex()}, or {@link #hasPatterns()}.
   */
  public List<String> toPrefixes() {
    List<String> subtrees =
        new ArrayList<>(
            scopes.stream()
                .filter(s -> s.kind == Kind.SUBTREE)
                .map(s -> s.uri)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
    return subtrees.stream()
        .filter(p -> subtrees.stream().noneMatch(q -> p.startsWith(q + "/")))
        .collect(Collectors.toUnmodifiableList());
  }

  /** The pattern scopes' URIs (with {@code _} segments). Empty when every scope is a prefix. */
  public List<String> patterns() {
    return scopes.stream()
        .filter(s -> s.kind == Kind.PATTERN)
        .map(s -> s.uri)
        .collect(Collectors.toUnmodifiableList());
  }

  /** The exact scopes' URIs. The API does not return exact scopes today. */
  public List<String> exact() {
    return scopes.stream()
        .filter(s -> s.kind == Kind.EXACT)
        .map(s -> s.uri)
        .collect(Collectors.toUnmodifiableList());
  }

  /**
   * True when some scope is a pattern, so {@link #toPrefixes()} alone does not cover everything.
   */
  public boolean hasPatterns() {
    return scopes.stream().anyMatch(s -> s.kind == Kind.PATTERN);
  }

  /**
   * One anchored regular expression source that matches exactly the URIs {@link #allows} accepts,
   * for stores that filter by regex (MongoDB {@code $regex}, PostgreSQL {@code ~}). The syntax is
   * shared by Java, JavaScript, PCRE and PostgreSQL. With no scopes it matches nothing. It is the
   * same string permio-js's {@code toRegExp().source} produces.
   */
  public String toRegex() {
    List<String> parts = new ArrayList<>();
    for (Scope s : scopes) {
      String part = regexSource(s);
      if (part != null) {
        parts.add(part);
      }
    }
    // "$.^" can never match, and is valid in Java, JavaScript, PCRE (MongoDB) and PostgreSQL.
    return parts.isEmpty() ? "$.^" : "^(?:" + String.join("|", parts) + ")$";
  }

  /** {@link #toRegex()} compiled as a Java {@link Pattern}. */
  public Pattern toPattern() {
    return Pattern.compile(toRegex());
  }

  @Override
  public String toString() {
    return "AccessScopes{under=" + under + ", coversAll=" + coversAll + ", scopes=" + scopes + "}";
  }

  // Splits "/a/b" into ["a", "b"]. Returns null for anything that is not a well-formed URI (no
  // leading slash, empty segments), so it can never match.
  private static String[] segments(String uri) {
    if (uri == null || !uri.startsWith("/") || uri.length() < 2) {
      return null;
    }
    String[] parts = uri.substring(1).split("/", -1);
    for (String p : parts) {
      if (p.isEmpty()) {
        return null;
      }
    }
    return parts;
  }

  private static boolean matches(Scope scope, String[] target) {
    String[] own = segments(scope.uri);
    if (own == null) {
      return false;
    }
    switch (scope.kind) {
      case EXACT:
        if (own.length != target.length) {
          return false;
        }
        for (int i = 0; i < own.length; i++) {
          if (!own[i].equals(target[i])) {
            return false;
          }
        }
        return true;
      case SUBTREE:
        // Segment by segment, so /a/b covers /a/b/c but not /a/bc. A "_" here is literal.
        if (own.length > target.length) {
          return false;
        }
        for (int i = 0; i < own.length; i++) {
          if (!own[i].equals(target[i])) {
            return false;
          }
        }
        return true;
      case PATTERN:
        if (own.length > target.length) {
          return false;
        }
        for (int i = 0; i < own.length; i++) {
          if (!own[i].equals(WILDCARD_SEGMENT) && !own[i].equals(target[i])) {
            return false;
          }
        }
        return true;
      default:
        return false;
    }
  }

  private static final String REGEX_META = ".*+?^${}()|[]\\/";

  private static String escape(String text) {
    StringBuilder sb = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (REGEX_META.indexOf(c) >= 0) {
        sb.append('\\');
      }
      sb.append(c);
    }
    return sb.toString();
  }

  private static String regexSource(Scope scope) {
    String[] own = segments(scope.uri);
    if (own == null) {
      return null;
    }
    StringBuilder body = new StringBuilder();
    for (String s : own) {
      body.append("\\/");
      body.append(scope.kind == Kind.PATTERN && s.equals(WILDCARD_SEGMENT) ? "[^/]+" : escape(s));
    }
    switch (scope.kind) {
      case EXACT:
        return body.toString();
      case SUBTREE:
      case PATTERN:
        return body + "(?:\\/.*)?";
      default:
        return null;
    }
  }
}
