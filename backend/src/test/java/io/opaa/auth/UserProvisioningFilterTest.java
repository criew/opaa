package io.opaa.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

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
            .claim("iss", "opaa-basic")
            .claim("preferred_username", "admin")
            .build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

    UserProvisioningFilter filter = new UserProvisioningFilter(userService);
    filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), filterChain);

    verify(userService).findOrCreateUser("admin", "opaa-basic", null, "admin");
    verify(filterChain).doFilter(any(), any());
    verifyNoMoreInteractions(userService);
  }
}
