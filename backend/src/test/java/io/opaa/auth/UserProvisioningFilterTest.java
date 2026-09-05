package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class UserProvisioningFilterTest {

  @Mock private UserService userService;
  @Mock private FilterChain filterChain;

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void provisionsUserWhenIssuerClaimIsNonUrlString() throws Exception {
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "HS256")
            .claim("sub", "admin")
            .claim("iss", "opaa-dev")
            .claim("preferred_username", "admin")
            .build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    User provisioned = new User("admin", "opaa-dev", null, "admin");
    when(userService.findOrCreateUser("admin", "opaa-dev", null, "admin")).thenReturn(provisioned);

    MockHttpServletRequest request = new MockHttpServletRequest();
    UserProvisioningFilter filter = new UserProvisioningFilter(userService);
    filter.doFilter(request, new MockHttpServletResponse(), filterChain);

    verify(userService).findOrCreateUser("admin", "opaa-dev", null, "admin");
    verify(filterChain).doFilter(any(), any());
    verifyNoMoreInteractions(userService);

    // The CurrentUserArgumentResolver contract (ADR-0005): the filter must set exactly the
    // provisioned user's snapshot as the request attribute the resolver later reads.
    Object attribute = request.getAttribute(CurrentUserArgumentResolver.REQUEST_ATTRIBUTE);
    assertThat(attribute)
        .isEqualTo(
            CurrentUser.of(
                provisioned.getId(),
                provisioned.getOrganizationId(),
                provisioned.getSystemRole(),
                provisioned.getDisplayName(),
                provisioned.getEmail()));
  }
}
