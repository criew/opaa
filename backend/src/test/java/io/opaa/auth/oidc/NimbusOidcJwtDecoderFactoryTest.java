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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

/**
 * {@link NimbusOidcJwtDecoderFactory} against a local double (#1329, ADR-0025 Entscheidung 1 and
 * 3): the decoder built from a discovery document validates a token signed with the discovered key,
 * refuses a token whose {@code azp} names another client, passes one without {@code azp}, and a
 * discovery document whose {@code jwks_uri} points outside the allowlist yields no decoder at all.
 */
class NimbusOidcJwtDecoderFactoryTest {

  private HttpServer server;
  private String baseUrl;
  private String issuer;
  private RSAKey key;
  private String jwksPath = "/realms/opaa/certs";

  @BeforeEach
  void setUp() throws Exception {
    key = new RSAKeyGenerator(2048).keyID("k1").generate();
    String jwks = new JWKSet(key.toPublicJWK()).toString();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    issuer = baseUrl + "/realms/opaa";
    server.createContext(
        "/realms/opaa/.well-known/openid-configuration",
        exchange ->
            respond(
                exchange,
                "{\"issuer\":\""
                    + issuer
                    + "\",\"jwks_uri\":\""
                    + (jwksPath.startsWith("http") ? jwksPath : baseUrl + jwksPath)
                    + "\"}"));
    server.createContext("/realms/opaa/certs", exchange -> respond(exchange, jwks));
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  private NimbusOidcJwtDecoderFactory factory() {
    return new NimbusOidcJwtDecoderFactory(
        new OidcDiscoveryClient(
            new OidcAddressPolicy(new TargetAddressValidator(true, List.of("127.0.0.1")))));
  }

  private OidcProvider provider(String jwkSetUri) {
    return new OidcProvider(
        "Test", issuer, "opaa-frontend", jwkSetUri, OidcClaimMapping.keycloakDefaults());
  }

  @Test
  void aTokenOfTheDiscoveredKeyIsAcceptedAndItsClaimsAreReadable() throws Exception {
    JwtDecoder decoder = factory().create(provider(null));

    assertThat(decoder.decode(token(issuer, "opaa-frontend")).getSubject()).isEqualTo("alice");
  }

  @Test
  void theJwkSetOverrideSkipsDiscoveryEntirely() throws Exception {
    // no discovery context is needed when the override is given - prove it by breaking discovery
    jwksPath = "http://169.254.169.254/keys";

    JwtDecoder decoder = factory().create(provider(baseUrl + "/realms/opaa/certs"));

    assertThat(decoder.decode(token(issuer, null)).getSubject()).isEqualTo("alice");
  }

  @Test
  void aTokenIssuedToAnotherClientIsRefusedByItsAzpClaim() throws Exception {
    JwtDecoder decoder = factory().create(provider(null));

    assertThatThrownBy(() -> decoder.decode(token(issuer, "other-app")))
        .isInstanceOf(JwtValidationException.class)
        .hasMessageContaining("azp");
  }

  @Test
  void aTokenOfAnotherIssuerIsRefused() throws Exception {
    JwtDecoder decoder = factory().create(provider(null));

    assertThatThrownBy(() -> decoder.decode(token("https://elsewhere.example", "opaa-frontend")))
        .isInstanceOf(JwtValidationException.class);
  }

  @Test
  void aDiscoveredJwksAddressOutsideTheAllowlistYieldsNoDecoder() {
    jwksPath = "http://169.254.169.254/keys";

    assertThatThrownBy(() -> factory().create(provider(null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OPAA_OIDC_TARGET_VALIDATION_ALLOWLIST");
  }

  private String token(String tokenIssuer, String azp) throws Exception {
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .issuer(tokenIssuer)
            .subject("alice")
            .issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(Instant.now().plusSeconds(300)));
    if (azp != null) {
      claims.claim("azp", azp);
    }
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims.build());
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
