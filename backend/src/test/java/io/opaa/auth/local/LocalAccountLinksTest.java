package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.common.PublicBaseUrl;
import io.opaa.common.PublicBaseUrlProperties;
import org.junit.jupiter.api.Test;

/**
 * The SPA paths behind the links of the local account management are the routes of #1540 - English
 * paths, the token as the {@code token} query - and fall back to a path relative to the
 * installation while no public base URL is configured.
 */
class LocalAccountLinksTest {

  private static final PublicBaseUrl CONFIGURED =
      new PublicBaseUrl(new PublicBaseUrlProperties("https://opaa.amt.example/"));
  private static final PublicBaseUrl NONE = new PublicBaseUrl(new PublicBaseUrlProperties(""));

  @Test
  void theSetPasswordAndVerifyEmailLinksPointAtTheSpaRoutes() {
    assertThat(LocalAccountLinks.SET_PASSWORD_PATH).isEqualTo("set-password");
    assertThat(LocalAccountLinks.VERIFY_EMAIL_PATH).isEqualTo("verify-email");
    assertThat(LocalAccountLinks.setPasswordLink(CONFIGURED, "a+b"))
        .isEqualTo("https://opaa.amt.example/set-password?token=a%2Bb");
    assertThat(LocalAccountLinks.verifyEmailLink(CONFIGURED, "a+b"))
        .isEqualTo("https://opaa.amt.example/verify-email?token=a%2Bb");
  }

  @Test
  void withoutABaseUrlTheLinksAreRelativeToTheInstallation() {
    assertThat(LocalAccountLinks.setPasswordLink(NONE, "t")).isEqualTo("/set-password?token=t");
    assertThat(LocalAccountLinks.verifyEmailLink(NONE, "t")).isEqualTo("/verify-email?token=t");
  }
}
