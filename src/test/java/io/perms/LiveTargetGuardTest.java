package io.perms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The live suite's production guard (it runs in the unit tests, without an API key). */
class LiveTargetGuardTest {
  @Test
  void allowsOnlyDevAndLocalHosts() {
    for (String ok :
        new String[] {
          "https://dev-api.perms.io",
          "https://dev-api.perms.io/",
          "https://DEV-API.perms.io:443",
          "https://dev-api.perms.io.",
          "http://localhost:8080",
          "http://127.0.0.1:9000",
          "http://[::1]:9000",
          "http://permio.localhost",
          "https://api.permio.test",
        }) {
      assertThat(LiveIntegrationTest.isAllowedTarget(ok)).as(ok).isTrue();
    }
    for (String bad :
        new String[] {
          Permio.DEFAULT_BASE_URL,
          "https://api.perms.io/",
          "https://API.PERMS.IO",
          "https://temp-api.perms.io",
          "https://api.perms.io.",
          "https://api.perms.io:443",
          "https://api.perms.io#dev-api.perms.io",
          "https://api.perms.io/?dev-api.perms.io",
          "https://api.perms.io/dev-api.perms.io",
          "https://dev-api.perms.io@api.perms.io",
          "https://dev-api.perms.io.evil.example",
          "https://localhost.evil.example",
          "https://evil-test.example",
          "https://.test",
          "grpc://dev-api.perms.io",
          "dev-api.perms.io",
          "",
          "not a url",
        }) {
      assertThat(LiveIntegrationTest.isAllowedTarget(bad)).as(bad).isFalse();
    }
  }
}
