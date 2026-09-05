package io.opaa.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ConflictException;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.TestPropertySource;

/**
 * {@link OidcProviderService} and {@link OidcProviderRegistry} against a real Postgres with the
 * versioned schema (#1329, ADR-0025): the two schema invariants ({@code
 * uq_oidc_providers_issuer_uri}, {@code ux_oidc_providers_single_default}) hold under the service,
 * every change writes an audit row, and - the acceptance criterion of #1329 - a provider created
 * through the service is able to authenticate a token signed with its own key <em>without a
 * restart</em>: the registry rebuilt itself after the commit, and a provider disabled afterwards is
 * refused on the next token.
 */
// Own context (AGENTS.md, "Spring-Testkontexte"): the address policy resolves every issuer host,
// and this test's issuers are a fictitious host plus a loopback JWKS server - both refused by the
// shared context's default policy, so the allowlist has to be widened for this class alone.
@OpaaIntegrationTest
@TestPropertySource(properties = "opaa.auth.oidc.target-validation.allowlist=idp.example,127.0.0.1")
class OidcProviderServiceIntegrationTest {

  @Autowired private OidcProviderService service;
  @Autowired private OidcProviderRegistry registry;
  @Autowired private OidcProviderRepository repository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private UUID userId;
  private HttpServer jwks;
  private RSAKey key;
  private String issuer;

  @BeforeEach
  void setUp() throws Exception {
    jdbcTemplate.update("DELETE FROM oidc_providers");
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "OIDC Test Org")).getId();
    User user = new User(UUID.randomUUID().toString(), "test-issuer", "oidc@example.com", "Test");
    user.setOrganizationId(organizationId);
    userId = userRepository.save(user).getId();

    key = new RSAKeyGenerator(2048).keyID("k1").generate();
    String jwksBody = new JWKSet(key.toPublicJWK()).toString();
    jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    jwks.createContext(
        "/certs",
        exchange -> {
          byte[] bytes = jwksBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    jwks.start();
    // the issuer is never fetched here: the JWK set override is the only address the registry
    // reads for this provider, exactly the Compose split ADR-0025 describes
    issuer = "https://idp.example/realms/" + UUID.randomUUID();
  }

  @AfterEach
  void tearDown() {
    jwks.stop(0);
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    jdbcTemplate.update("DELETE FROM oidc_providers");
    registry.refresh();
    userRepository.deleteById(userId);
    organizationRepository.deleteById(organizationId);
  }

  private OidcProviderDraft draft(String name, String issuerUri) {
    return new OidcProviderDraft(
        name,
        issuerUri,
        "opaa-frontend",
        "http://127.0.0.1:" + jwks.getAddress().getPort() + "/certs",
        OidcClaimMapping.keycloakDefaults());
  }

  @Test
  void aProviderCreatedThroughTheServiceAuthenticatesTokensWithoutARestart() throws Exception {
    service.createProvider(organizationId, userId, draft("Verzeichnisdienst", issuer));

    assertThat(registry.findEnabledByIssuer(issuer))
        .as("registry rebuilt after commit")
        .isPresent();
    AuthenticationManager manager = registry.resolve(issuer);
    Authentication authenticated =
        manager.authenticate(new BearerTokenAuthenticationToken(signedToken(issuer)));
    assertThat(authenticated).isInstanceOf(JwtAuthenticationToken.class);
    assertThat(((JwtAuthenticationToken) authenticated).getToken().getSubject()).isEqualTo("alice");
  }

  @Test
  void aDisabledProviderIsRefusedOnTheNextToken() {
    OidcProvider first = service.createProvider(organizationId, userId, draft("Erster", issuer));
    String second = issuer + "-2";
    OidcProvider partner = service.createProvider(organizationId, userId, draft("Partner", second));
    assertThat(registry.findEnabledByIssuer(second)).isPresent();

    service.setEnabled(organizationId, userId, partner.getId(), false);

    assertThat(registry.findEnabledByIssuer(second)).isEmpty();
    assertThatThrownBy(
            () ->
                registry
                    .resolve(second)
                    .authenticate(new BearerTokenAuthenticationToken(signedToken(second))))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .hasMessageContaining(OidcProviderRegistry.UNKNOWN_ISSUER);
    assertThat(registry.findEnabledByIssuer(first.getIssuerUri())).isPresent();
  }

  @Test
  void theSchemaKeepsTheIssuerUniqueAndTheDefaultSingular() {
    service.createProvider(organizationId, userId, draft("Erster", issuer));

    assertThatThrownBy(() -> service.createProvider(organizationId, userId, draft("Kopie", issuer)))
        .isInstanceOf(ConflictException.class);
    OidcProvider partner =
        service.createProvider(organizationId, userId, draft("Partner", issuer + "-2"));
    service.makeDefault(organizationId, userId, partner.getId());

    List<OidcProvider> all = repository.findAllByOrderBySortOrderAscDisplayNameAsc();
    assertThat(all).filteredOn(OidcProvider::isDefaultProvider).singleElement();
    assertThat(all).filteredOn(OidcProvider::isDefaultProvider).first().isEqualTo(partner);
  }

  @Test
  void everyChangeLeavesOneAuditRowOfItsOwnType() {
    OidcProvider created = service.createProvider(organizationId, userId, draft("Erster", issuer));
    service.updateProvider(organizationId, userId, created.getId(), draft("Umbenannt", issuer));
    OidcProvider partner =
        service.createProvider(organizationId, userId, draft("Partner", issuer + "-2"));
    service.setEnabled(organizationId, userId, partner.getId(), false);
    service.setEnabled(organizationId, userId, partner.getId(), true);
    service.deleteProvider(organizationId, userId, partner.getId());

    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT event_type FROM audit_log WHERE organization_id = ? ORDER BY recorded_at, event_id",
            organizationId);
    assertThat(rows)
        .extracting(row -> row.get("event_type"))
        .containsExactly(
            "OIDC_PROVIDER_CREATED",
            "OIDC_PROVIDER_CHANGED",
            "OIDC_PROVIDER_CREATED",
            "OIDC_PROVIDER_DISABLED",
            "OIDC_PROVIDER_ENABLED",
            "OIDC_PROVIDER_DELETED");
  }

  private String signedToken(String tokenIssuer) throws Exception {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(tokenIssuer)
            .subject("alice")
            .issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .claim("email", "alice@example.com")
            .build();
    SignedJWT jwt =
        new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }
}
