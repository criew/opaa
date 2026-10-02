package io.opaa.auth;

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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opaa.security.RebindingHostLookup;
import io.opaa.security.TargetAddressValidator;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Regression guard for #1860: every provider address - the issuer, the JWK set override and the
 * {@code jwks_uri} of a discovery document - is held to the address policy at connect time, not
 * only when it is checked. {@code localhost} answers a public address to the check and the loopback
 * to the connection ({@link RebindingHostLookup}); the request must be refused before it reaches
 * the local server listening there.
 */
class OidcDnsRebindingTest {

  private static final String ALLOWLIST_HINT = "OPAA_OIDC_TARGET_VALIDATION_ALLOWLIST";
  private static final String BLOCKED = "gesperrten Adressbereich";

  private final AtomicInteger discoveryRequests = new AtomicInteger();
  private final AtomicInteger jwkSetRequests = new AtomicInteger();
  private HttpServer server;
  private int port;
  private RSAKey key;
  private RebindingHostLookup lookup;

  @BeforeEach
  void setUp() throws Exception {
    key = new RSAKeyGenerator(2048).keyID("k1").generate();
    String jwks = new JWKSet(key.toPublicJWK()).toString();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    port = server.getAddress().getPort();
    server.createContext(
        "/realms/opaa/.well-known/openid-configuration",
        exchange -> {
          discoveryRequests.incrementAndGet();
          respond(
              exchange,
              "{\"issuer\":\""
                  + allowedIssuer()
                  + "\",\"jwks_uri\":\""
                  + rebindingUrl("/realms/opaa/certs")
                  + "\"}");
        });
    server.createContext(
        "/realms/opaa/certs",
        exchange -> {
          jwkSetRequests.incrementAndGet();
          respond(exchange, jwks);
        });
    server.start();
    lookup = new RebindingHostLookup("localhost");
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  /** The policy of the tests: {@code 127.0.0.1} is allowed, {@code localhost} rebinds. */
  private OidcDiscoveryClient discoveryClient() {
    return new OidcDiscoveryClient(
        new OidcAddressPolicy(new TargetAddressValidator(true, List.of("127.0.0.1"), lookup)));
  }

  private String allowedIssuer() {
    return "http://127.0.0.1:" + port + "/realms/opaa";
  }

  private String rebindingUrl(String path) {
    return "http://localhost:" + port + path;
  }

  @Test
  void anIssuerThatRebindsAfterTheCheckIsRefusedWithoutAnyRequest() {
    assertThatThrownBy(() -> discoveryClient().fetchDiscovery(rebindingUrl("/realms/opaa")))
        .isInstanceOfSatisfying(
            OidcDiscoveryClient.OidcProbeException.class,
            e -> assertThat(e.isUnreachable()).isFalse())
        .hasMessageContaining(BLOCKED)
        .hasMessageContaining(ALLOWLIST_HINT);

    assertThat(discoveryRequests).hasValue(0);
    assertThat(lookup.lookups()).isEqualTo(2);
  }

  @Test
  void aJwkSetOverrideThatRebindsAfterTheCheckIsRefusedWithoutAnyRequest() {
    assertThatThrownBy(() -> discoveryClient().fetchJwkSet(rebindingUrl("/realms/opaa/certs")))
        .isInstanceOf(OidcDiscoveryClient.OidcProbeException.class)
        .hasMessageContaining(BLOCKED);

    assertThat(jwkSetRequests).hasValue(0);
  }

  @Test
  void theDecoderRefusesAJwkSetOverrideThatRebindsAfterTheCheck() throws Exception {
    OidcProvider provider =
        new OidcProvider(
            "Test",
            allowedIssuer(),
            "opaa-frontend",
            rebindingUrl("/realms/opaa/certs"),
            OidcClaimMapping.keycloakDefaults());
    OidcDiscoveryClient discovery = discoveryClient();
    // what OidcProviderRegistry does before it builds a decoder
    discovery.addressPolicy().requireAllowed(provider.getJwkSetUri(), "JWK-Set-URI");
    JwtDecoder decoder = new NimbusOidcJwtDecoderFactory(discovery).create(provider);

    assertThatThrownBy(() -> decoder.decode(token())).isInstanceOf(JwtException.class);
    assertThat(jwkSetRequests).hasValue(0);
  }

  @Test
  void theDecoderRefusesADiscoveredJwksUriThatRebindsAfterTheCheck() throws Exception {
    OidcProvider provider =
        new OidcProvider(
            "Test", allowedIssuer(), "opaa-frontend", null, OidcClaimMapping.keycloakDefaults());

    JwtDecoder decoder = new NimbusOidcJwtDecoderFactory(discoveryClient()).create(provider);

    assertThat(discoveryRequests).hasValue(1);
    assertThatThrownBy(() -> decoder.decode(token())).isInstanceOf(JwtException.class);
    assertThat(jwkSetRequests).hasValue(0);
    assertThat(lookup.lookups()).isEqualTo(2);
  }

  private String token() throws Exception {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(allowedIssuer())
            .subject("alice")
            .issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .build();
    SignedJWT jwt =
        new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  private static void respond(HttpExchange exchange, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
