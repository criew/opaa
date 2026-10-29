package io.opaa.externalaccess.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.auth.RequiresRecentLogin;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Issuing an access token requires a recent sign-in - a token outlives the session it came from -
 * while listing and revoking one's own tokens do not: revoking stays possible from any session.
 */
class ExternalAccessTokenIssuanceRecentLoginTest {

  @Test
  void onlyTheIssuanceRequiresARecentSignIn() {
    assertThat(ExternalAccessTokenController.class.isAnnotationPresent(RequiresRecentLogin.class))
        .isFalse();
    assertThat(
            Arrays.stream(ExternalAccessTokenController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(RequiresRecentLogin.class))
                .map(Method::getName))
        .containsExactly("createExternalAccessToken");
  }
}
