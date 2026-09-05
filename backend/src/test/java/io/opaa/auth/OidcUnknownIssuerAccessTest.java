package io.opaa.auth;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.opaa.auth.oidc.OidcAddressPolicy;
import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcJwtDecoderFactory;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRegistry;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.sourceaccess.TargetAddressValidator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The real {@link OidcSecurityConfig} chain over a real {@link OidcProviderRegistry} (#1329,
 * ADR-0025 Entscheidung 1): a token of an enabled provider is provisioned by {@link
 * UserProvisioningFilter} - exactly once, and the controller answers from that snapshot; a token of
 * an issuer no enabled provider owns - a disabled or deleted one - is refused with {@code 401} and
 * {@code error_description="unknown_issuer"} in {@code WWW-Authenticate}, which is what the SPA
 * tells apart from an expired token. Only the decoder is a test double; the resolver, the registry
 * and the filter order are the production wiring.
 */
@WebMvcTest(controllers = UserInfoController.class)
@Import({OidcSecurityConfig.class, OidcUnknownIssuerAccessTest.RegistryStub.class})
@ActiveProfiles("oidc")
class OidcUnknownIssuerAccessTest {

  static final String ENABLED_ISSUER = "https://idp.example/realms/beschaeftigte";
  static final String DISABLED_ISSUER = "https://idp.example/realms/alt";
  private static RSAKey key;

  @TestConfiguration
  static class RegistryStub {
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
      UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
      source.registerCorsConfiguration("/api/**", new CorsConfiguration());
      return source;
    }

    /** One enabled provider whose decoder accepts any token carrying its issuer. */
    @Bean
    AuthenticationManagerResolver<HttpServletRequest> oidcAuthenticationManagerResolver() {
      OidcProvider enabled =
          new OidcProvider(
              "Beschäftigte",
              ENABLED_ISSUER,
              "opaa-frontend",
              null,
              OidcClaimMapping.keycloakDefaults());
      OidcProviderRepository repository = mock(OidcProviderRepository.class);
      when(repository.findAllByEnabledTrueOrderBySortOrderAscDisplayNameAsc())
          .thenReturn(List.of(enabled));
      OidcJwtDecoderFactory decoderFactory =
          provider ->
              (JwtDecoder)
                  token ->
                      Jwt.withTokenValue(token)
                          .header("alg", "RS256")
                          .claim("iss", ENABLED_ISSUER)
                          .claim("sub", "alice")
                          .claim("email", "alice@behoerde.example")
                          .issuedAt(Instant.now())
                          .expiresAt(Instant.now().plusSeconds(300))
                          .build();
      OidcProviderRegistry registry =
          new OidcProviderRegistry(
              repository,
              decoderFactory,
              new OidcAddressPolicy(TargetAddressValidator.disabled()),
              Clock.systemUTC());
      return new JwtIssuerAuthenticationManagerResolver(registry);
    }
  }

  @Autowired private MockMvc mockMvc;
  @MockitoBean private UserService userService;

  @BeforeAll
  static void generateKey() throws Exception {
    key = new RSAKeyGenerator(2048).keyID("k1").generate();
  }

  @Test
  void aTokenOfAnEnabledProviderIsProvisionedOnceByTheFilterAndAnsweredFromThatSnapshot()
      throws Exception {
    User alice = new User("alice", ENABLED_ISSUER, "alice@behoerde.example", "Alice");
    when(userService.findOrCreateUser(any(), any(), any(), any())).thenReturn(alice);

    mockMvc
        .perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token(ENABLED_ISSUER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("alice@behoerde.example"))
        .andExpect(jsonPath("$.displayName").value("Alice"));
    // the filter's load is the only one; without the filter @Caller has nothing to resolve
    verify(userService, times(1))
        .findOrCreateUser(eq("alice"), eq(ENABLED_ISSUER), eq("alice@behoerde.example"), any());
  }

  @Test
  void aTokenOfAnIssuerNoEnabledProviderOwnsIsRefusedAsUnknownIssuer() throws Exception {
    mockMvc
        .perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token(DISABLED_ISSUER)))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", containsString("error=\"invalid_token\"")))
        .andExpect(
            header()
                .string(
                    "WWW-Authenticate", containsString("error_description=\"unknown_issuer\"")));
  }

  @Test
  void aTokenWithoutAnyIssuerIsRefused() throws Exception {
    mockMvc
        .perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token(null)))
        .andExpect(status().isUnauthorized());
  }

  private static String token(String issuer) throws Exception {
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .subject("alice")
            .claim("email", "alice@behoerde.example")
            .issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(Instant.now().plusSeconds(300)));
    if (issuer != null) {
      claims.issuer(issuer);
    }
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims.build());
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }
}
