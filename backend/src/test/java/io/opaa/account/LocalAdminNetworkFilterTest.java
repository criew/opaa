package io.opaa.account;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.auth.LocalIssuer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

/**
 * The network rule of a local system administrator holds for every request, not only the sign-in.
 */
class LocalAdminNetworkFilterTest {

  private final LocalAdminNetworkPolicy policy =
      new LocalAdminNetworkPolicy(
          new LocalAuthProperties(
              null, null, null, null, null, null, null, null, null, List.of("10.0.0.0/8"), null));
  private final LocalAdminNetworkFilter filter =
      new LocalAdminNetworkFilter(
          policy,
          request -> request.getRemoteAddr(),
          JsonMapper.builder().build(),
          Clock.systemUTC());

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private static void signedIn(String issuer, String role) {
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "HS256")
            .claim("iss", issuer)
            .subject("4f6a4b0e-2d1c-4c39-9a51-7f3c1b2a9e10")
            .issuedAt(Instant.now())
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role))));
  }

  private MockHttpServletResponse call(String path, String from) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setRemoteAddr(from);
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    if (chain.getRequest() != null) {
      response.setStatus(200);
    }
    return response;
  }

  // regression guard for #3358 (6): a local administrator's token is refused outside the networks
  @Test
  void aLocalAdministratorIsRefusedOutsideTheNetworks() throws Exception {
    signedIn(LocalIssuer.URN, "ROLE_SYSTEM_ADMIN");

    MockHttpServletResponse response = call("/api/v1/admin/users", "192.0.2.10");

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains(LocalAdminNetworkFilter.CODE);
  }

  @Test
  void aLocalAdministratorPassesInsideTheNetworksAndMaySignOutFromAnywhere() throws Exception {
    signedIn(LocalIssuer.URN, "ROLE_SYSTEM_ADMIN");

    assertThat(call("/api/v1/admin/users", "10.1.2.3").getStatus()).isEqualTo(200);
    assertThat(call("/api/v1/auth/local/logout", "192.0.2.10").getStatus()).isEqualTo(200);
  }

  @Test
  void otherAccountsAndProvidersAreNotRestricted() throws Exception {
    signedIn(LocalIssuer.URN, "ROLE_USER");
    assertThat(call("/api/v1/spaces", "192.0.2.10").getStatus()).isEqualTo(200);

    signedIn("https://sso.example.org/realms/haus", "ROLE_SYSTEM_ADMIN");
    assertThat(call("/api/v1/admin/users", "192.0.2.10").getStatus()).isEqualTo(200);
  }
}
