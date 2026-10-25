package io.opaa.installation.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.common.AccessDeniedException;
import io.opaa.common.TooManyRequestsException;
import io.opaa.common.UnauthorizedException;
import io.opaa.installation.InstallationProperties;
import io.opaa.installation.InstallationService;
import io.opaa.security.TrustedProxyClientIpResolver;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The door of the installation API: the allowed networks, the token and the budget of refused
 * tokens per client address, past which even the right token waits.
 */
class InstallationAccessGuardTest {

  private static final String TOKEN = "richtiges-token";

  private final InstallationService installation = mock(InstallationService.class);
  private final TrustedProxyClientIpResolver addresses =
      new TrustedProxyClientIpResolver(List.of());

  InstallationAccessGuardTest() {
    when(installation.tokenMatches(any())).thenAnswer(call -> TOKEN.equals(call.getArgument(0)));
  }

  @Test
  void theRightTokenPassesAndAMissingOrWrongOneDoesNot() {
    InstallationAccessGuard guard = guard(List.of());

    assertThatCode(() -> guard.requireAccess(request("10.1.1.1", TOKEN)))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> guard.requireAccess(request("10.1.1.1", null)))
        .isInstanceOf(UnauthorizedException.class);
    assertThatThrownBy(() -> guard.requireAccess(request("10.1.1.1", "falsch")))
        .isInstanceOf(UnauthorizedException.class);
  }

  @Test
  void aClientOutsideTheAllowedNetworksIsRefusedEvenWithTheToken() {
    InstallationAccessGuard guard = guard(List.of("10.0.0.0/8"));

    assertThatCode(() -> guard.requireAccess(request("10.2.3.4", TOKEN)))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> guard.requireAccess(request("192.168.1.1", TOKEN)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void pastTenRefusedTokensTheAddressWaitsEvenWithTheRightOne() {
    InstallationAccessGuard guard = guard(List.of());
    for (int i = 0; i < InstallationAccessGuard.MAX_REFUSED_TOKENS; i++) {
      assertThatThrownBy(() -> guard.requireAccess(request("10.9.9.9", "geraten")))
          .isInstanceOf(UnauthorizedException.class);
    }

    assertThatThrownBy(() -> guard.requireAccess(request("10.9.9.9", TOKEN)))
        .isInstanceOf(TooManyRequestsException.class);
    assertThatCode(() -> guard.requireAccess(request("10.9.9.10", TOKEN)))
        .doesNotThrowAnyException();
  }

  @Test
  void acceptedTokensDoNotUseUpTheBudgetOfRefusals() {
    InstallationAccessGuard guard = guard(List.of());

    for (int i = 0; i < 3 * InstallationAccessGuard.MAX_REFUSED_TOKENS; i++) {
      assertThatCode(() -> guard.requireAccess(request("10.8.8.8", TOKEN)))
          .doesNotThrowAnyException();
    }
  }

  private InstallationAccessGuard guard(List<String> cidrs) {
    return new InstallationAccessGuard(
        installation, addresses, new InstallationProperties(null, cidrs, null));
  }

  private static MockHttpServletRequest request(String address, String token) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/installation");
    request.setRemoteAddr(address);
    if (token != null) {
      request.addHeader("Authorization", "Bearer " + token);
    }
    return request;
  }
}
