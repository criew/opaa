package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.servlet.FilterChain;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

@ExtendWith(MockitoExtension.class)
class DevAuthFilterTest {

  private static final AuthProperties.DevAuth DEV_AUTH =
      new AuthProperties.DevAuth(
          "opaa-dev",
          "dev-admin",
          List.of(
              new AuthProperties.DevUser("dev-admin", "admin@opaa.local", "Dev Admin"),
              new AuthProperties.DevUser("dev-user", "dev-user@opaa.local", "Dev User")));

  @Mock private FilterChain filterChain;

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void authenticatesAsDefaultUserWithoutHeader() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    new DevAuthFilter(DEV_AUTH).doFilter(new MockHttpServletRequest(), response, filterChain);

    Jwt jwt = principal();
    assertThat(jwt.getSubject()).isEqualTo("dev-admin");
    assertThat(jwt.getClaimAsString("iss")).isEqualTo("opaa-dev");
    assertThat(jwt.getClaimAsString("email")).isEqualTo("admin@opaa.local");
    assertThat(jwt.getClaimAsString("name")).isEqualTo("Dev Admin");
    verify(filterChain).doFilter(any(), any());
  }

  @Test
  void authenticatesAsUserNamedInHeader() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(DevAuthFilter.DEV_USER_HEADER, "dev-user");

    new DevAuthFilter(DEV_AUTH).doFilter(request, new MockHttpServletResponse(), filterChain);

    assertThat(principal().getSubject()).isEqualTo("dev-user");
    assertThat(principal().getClaimAsString("email")).isEqualTo("dev-user@opaa.local");
  }

  @Test
  void rejectsUnknownUserInsteadOfFallingBackToTheDefault() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(DevAuthFilter.DEV_USER_HEADER, "does-not-exist");
    MockHttpServletResponse response = new MockHttpServletResponse();

    new DevAuthFilter(DEV_AUTH).doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verifyNoInteractions(filterChain);
  }

  private Jwt principal() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    assertThat(authentication).isNotNull();
    assertThat(authentication.getPrincipal()).isInstanceOf(Jwt.class);
    return (Jwt) authentication.getPrincipal();
  }
}
