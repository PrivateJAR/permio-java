package io.perms;

import static org.assertj.core.api.Assertions.assertThat;

import io.perms.AccessScopes.Kind;
import io.perms.AccessScopes.Scope;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccessScopesTest {
  private static AccessScopes scopes(Scope... s) {
    return new AccessScopes("/docs", false, List.of(s));
  }

  @Test
  void subtreeMatchesPerSegment() {
    AccessScopes a = scopes(new Scope("/a/b", Kind.SUBTREE));
    assertThat(a.allows("/a/b")).isTrue();
    assertThat(a.allows("/a/b/c")).isTrue();
    assertThat(a.allows("/a/bc")).isFalse();
    assertThat(a.allows("/a")).isFalse();
    assertThat(a.allows("a/b")).isFalse();
    assertThat(a.allows("/a/b/")).isFalse();
  }

  @Test
  void patternMatchesOneSegmentPerWildcard() {
    AccessScopes a = scopes(new Scope("/a/_/c", Kind.PATTERN));
    assertThat(a.allows("/a/x/c")).isTrue();
    assertThat(a.allows("/a/x/c/d")).isTrue();
    assertThat(a.allows("/a/x")).isFalse();
    assertThat(a.allows("/a/x/y")).isFalse();
    assertThat(a.hasPatterns()).isTrue();
    assertThat(a.patterns()).containsExactly("/a/_/c");
  }

  @Test
  void underscoreIsLiteralInSubtrees() {
    AccessScopes a = scopes(new Scope("/a/_", Kind.SUBTREE));
    assertThat(a.allows("/a/_")).isTrue();
    assertThat(a.allows("/a/x")).isFalse();
  }

  @Test
  void exactAndUnknown() {
    AccessScopes a = scopes(new Scope("/a", Kind.EXACT), new Scope("/b", Kind.UNKNOWN));
    assertThat(a.allows("/a")).isTrue();
    assertThat(a.allows("/a/b")).isFalse();
    assertThat(a.allows("/b")).isFalse();
    assertThat(a.exact()).containsExactly("/a");
    assertThat(scopes(new Scope("/b", Kind.UNKNOWN)).isEmpty()).isTrue();
    assertThat(scopes().isEmpty()).isTrue();
  }

  @Test
  void prefixesDropCoveredOnes() {
    AccessScopes a =
        scopes(
            new Scope("/docs/1", Kind.SUBTREE),
            new Scope("/docs/1/x", Kind.SUBTREE),
            new Scope("/docs/12", Kind.SUBTREE),
            new Scope("/docs/_/y", Kind.PATTERN));
    assertThat(a.toPrefixes()).containsExactly("/docs/1", "/docs/12");
  }

  @Test
  void regexMatchesExactlyWhatAllowsAccepts() {
    AccessScopes a =
        scopes(
            new Scope("/a.b/c", Kind.SUBTREE),
            new Scope("/p/_/q", Kind.PATTERN),
            new Scope("/e", Kind.EXACT));
    assertThat(a.toRegex())
        .isEqualTo("^(?:\\/a\\.b\\/c(?:\\/.*)?|\\/p\\/[^/]+\\/q(?:\\/.*)?|\\/e)$");
    for (String uri :
        new String[] {"/a.b/c", "/a.b/c/d", "/aXb/c", "/p/x/q", "/p/x/q/r", "/p/x", "/e", "/e/f"}) {
      assertThat(a.toPattern().matcher(uri).matches()).as(uri).isEqualTo(a.allows(uri));
    }
    assertThat(scopes().toRegex()).isEqualTo("$.^");
    assertThat(scopes().toPattern().matcher("").matches()).isFalse();
    assertThat(scopes().toPattern().matcher("/a").matches()).isFalse();
  }
}
