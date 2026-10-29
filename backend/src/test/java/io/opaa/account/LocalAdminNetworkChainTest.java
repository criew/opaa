package io.opaa.account;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.auth.web.UserInfoController;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real {@link OidcSecurityConfig} chain with the {@link LocalAdminNetworkFilter} in place: the
 * access token of a local system administrator reaches nothing from outside the released networks
 * but the sign-out. Only the token decoder and the sign-out handler are test doubles.
 */
@WebMvcTest(controllers = UserInfoController.class)
@Import({OidcSecurityConfig.class, LocalAdminNetworkChainTest.Chain.class})
@ActiveProfiles("oidc")
class LocalAdminNetworkChainTest {

  private static final String INSIDE = "10.20.30.40";
  private static final String OUTSIDE = "192.0.2.10";

  @TestConfiguration
  static class Chain {
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
      UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
      source.registerCorsConfiguration("/api/**", new CorsConfiguration());
      return source;
    }

    /** Every bearer token reads as an access token of this installation's own issuer. */
    @Bean
    AuthenticationManagerResolver<HttpServletRequest> oidcAuthenticationManagerResolver() {
      JwtDecoder decoder =
          token ->
              Jwt.withTokenValue(token)
                  .header("alg", "HS256")
                  .claim("iss", LocalIssuer.URN)
                  .subject("admin")
                  .issuedAt(Instant.now())
                  .expiresAt(Instant.now().plusSeconds(300))
                  .build();
      AuthenticationManager manager = new ProviderManager(new JwtAuthenticationProvider(decoder));
      return request -> manager;
    }

    @Bean
    LocalAdminNetworkFilter localAdminNetworkFilter(JsonMapper jsonMapper) {
      LocalAuthProperties properties =
          new LocalAuthProperties(
              null, null, null, null, null, null, null, null, null, List.of("10.0.0.0/8"), null);
      return new LocalAdminNetworkFilter(
          new LocalAdminNetworkPolicy(properties),
          HttpServletRequest::getRemoteAddr,
          jsonMapper,
          Clock.systemUTC());
    }

    @Bean
    SignOut signOut() {
      return new SignOut();
    }
  }

  /** Stands in for the sign-out of {@code LocalAuthController}; only its reachability counts. */
  @RestController
  static class SignOut {
    @PostMapping("/api/v1/auth/local/logout")
    ResponseEntity<Void> logout() {
      return ResponseEntity.noContent().build();
    }
  }

  @Autowired private MockMvc mockMvc;
  @MockitoBean private UserService userService;
  @MockitoBean private LocalAccountSelfDisclosure localAccountSelfDisclosure;

  @BeforeEach
  void signedInAsLocalAdministrator() {
    User admin = User.localAccount("admin@stadt.example", "Admin");
    admin.setSystemRole(SystemRole.SYSTEM_ADMIN);
    when(userService.provisionFromToken(any())).thenReturn(admin);
  }

  private static RequestPostProcessor from(String address) {
    return request -> {
      request.setRemoteAddr(address);
      request.addHeader("Authorization", "Bearer local-admin-token");
      return request;
    };
  }

  // regression guard for #3358 (6): the network rule holds on every request, not only the sign-in
  @Test
  void aLocalAdministratorsTokenReachesNothingFromOutsideTheNetworks() throws Exception {
    mockMvc
        .perform(get("/api/v1/auth/me").with(from(OUTSIDE)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(LocalAdminNetworkFilter.CODE));
  }

  @Test
  void insideTheNetworksTheTokenIsAnswered() throws Exception {
    mockMvc.perform(get("/api/v1/auth/me").with(from(INSIDE))).andExpect(status().isOk());
  }

  @Test
  void theSignOutStaysReachableFromAnywhere() throws Exception {
    mockMvc
        .perform(post("/api/v1/auth/local/logout").with(from(OUTSIDE)).with(csrf()))
        .andExpect(status().isNoContent());
  }
}
