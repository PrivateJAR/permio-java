package io.perms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ApiKeysTest {
  @Test
  void acceptsValidOpaqueKeys() {
    assertThat(ApiKeys.validate(TestServer.VALID_KEY)).isEqualTo(ApiKeys.Kind.OPAQUE);
    assertThat(ApiKeys.validate(TestServer.VALID_KEY_2)).isEqualTo(ApiKeys.Kind.OPAQUE);
    assertThat(TestServer.VALID_KEY).hasSize(ApiKeys.API_KEY_LENGTH);
  }

  @Test
  void acceptsLegacyJwtKeys() {
    assertThat(ApiKeys.validate(TestServer.JWT_KEY)).isEqualTo(ApiKeys.Kind.JWT);
  }

  @Test
  void rejectsMissingKey() {
    assertThatThrownBy(() -> ApiKeys.validate(null))
        .isInstanceOf(PermioConfigException.class)
        .hasMessageContaining("apiKey is required");
    assertThatThrownBy(() -> ApiKeys.validate("")).isInstanceOf(PermioConfigException.class);
  }

  @Test
  void rejectsWrongLength() {
    assertThatThrownBy(() -> ApiKeys.validate("perms_abc"))
        .isInstanceOf(PermioConfigException.class)
        .hasMessageContaining("expected 44 characters");
  }

  @Test
  void rejectsBadCharacters() {
    String bad = TestServer.VALID_KEY.substring(0, 10) + "-" + TestServer.VALID_KEY.substring(11);
    assertThatThrownBy(() -> ApiKeys.validate(bad))
        .isInstanceOf(PermioConfigException.class)
        .hasMessageContaining("0-9, A-Z or a-z");
  }

  @Test
  void rejectsChecksumMismatch() {
    // One character of the random part changed.
    String typo = "perms_1123456789ABCDEFGHIJKLMNOPQRSTUV1ggZdL";
    assertThatThrownBy(() -> ApiKeys.validate(typo))
        .isInstanceOf(PermioConfigException.class)
        .hasMessageContaining("checksum mismatch");
    assertThat(ApiKeys.isValid(typo)).isFalse();
  }

  @Test
  void rejectsOtherShapes() {
    assertThatThrownBy(() -> ApiKeys.validate("not-a-key"))
        .isInstanceOf(PermioConfigException.class)
        .hasMessageContaining("legacy JWT");
    assertThat(ApiKeys.isValid(TestServer.VALID_KEY)).isTrue();
  }
}
