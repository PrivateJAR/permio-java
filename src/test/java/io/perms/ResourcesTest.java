package io.perms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ResourcesTest {
  @Test
  void buildsUris() {
    assertThat(Resources.resource("docs")).isEqualTo("/docs");
    assertThat(Resources.resource("projects", "p1", "docs", 42)).isEqualTo("/projects/p1/docs/42");
    assertThat(Permio.resource("a", "b")).isEqualTo("/a/b");
  }

  @Test
  void encodesSegmentsLikeJs() {
    assertThat(Resources.encodeSegment("a/b")).isEqualTo("a%2Fb");
    assertThat(Resources.encodeSegment("a b")).isEqualTo("a%20b");
    assertThat(Resources.encodeSegment("!'()*")).isEqualTo("%21%27%28%29%2A");
    assertThat(Resources.encodeSegment("-_.~")).isEqualTo("-_.~");
    assertThat(Resources.encodeSegment("é")).isEqualTo("%C3%A9");
    assertThat(Resources.encodeSegment("😀")).isEqualTo("%F0%9F%98%80");
  }

  @Test
  void rejectsEmptySegments() {
    assertThatThrownBy(() -> Resources.resource()).isInstanceOf(PermioValidationException.class);
    assertThatThrownBy(() -> Resources.resource("a", ""))
        .isInstanceOf(PermioValidationException.class);
    assertThatThrownBy(() -> Resources.resource("a", null))
        .isInstanceOf(PermioValidationException.class);
    assertThatThrownBy(() -> Resources.encodeSegment("\ud800"))
        .isInstanceOf(PermioValidationException.class);
  }

  @Test
  void checksUriShape() {
    assertThatCode(() -> Resources.assertResourceUri("/docs/1")).doesNotThrowAnyException();
    assertThatCode(() -> Resources.assertResourceUri("/a/_/c")).doesNotThrowAnyException();
    for (String bad :
        new String[] {
          "", "docs", "/", "/docs/", "//docs", "/a//b", "/a b", "/a\tb", "/a b", "/a\u0085"
        }) {
      assertThatThrownBy(() -> Resources.assertResourceUri(bad))
          .as(bad)
          .isInstanceOf(PermioValidationException.class);
    }
    assertThatThrownBy(() -> Resources.assertResourceUri(null))
        .isInstanceOf(PermioValidationException.class);
    assertThatThrownBy(() -> Resources.assertResourceUri("/" + "a".repeat(2048)))
        .hasMessageContaining("longer than 2048 bytes");
  }
}
