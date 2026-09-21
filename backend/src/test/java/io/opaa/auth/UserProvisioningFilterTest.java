package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
    when(userService.provisionFromToken(jwt)).thenReturn(provisioned);

    MockHttpServletRequest request = new MockHttpServletRequest();
    UserProvisioningFilter filter = new UserProvisioningFilter(userService);
    filter.doFilter(request, new MockHttpServletResponse(), filterChain);

    verify(userService).provisionFromToken(jwt);
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

  /**
   * #1818: an account the directory synchronisation locked reaches nothing, however valid its token
   * still is - the refusal carries the marker the SPA turns into reason and contact instead of a
   * silent redirect, and the chain is not continued.
   */
  @Test
  void aDirectoryLockedAccountIsRefusedWithItsMarkerAndReachesNoHandler() throws Exception {
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "HS256")
            .claim("sub", "gone")
            .claim("iss", "https://idp.example/realms/a")
            .build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    User locked = new User("gone", "https://idp.example/realms/a", null, "Gone");
    locked.lockFromDirectory(Instant.now());
    when(userService.provisionFromToken(jwt)).thenReturn(locked);

    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    new UserProvisioningFilter(userService).doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
        .contains(UserProvisioningFilter.ACCOUNT_LOCKED_BY_DIRECTORY);
    assertThat(request.getAttribute(CurrentUserArgumentResolver.REQUEST_ATTRIBUTE)).isNull();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(filterChain, never()).doFilter(any(), any());
  }
}
