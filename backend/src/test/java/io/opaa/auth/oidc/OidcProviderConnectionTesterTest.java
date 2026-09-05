package io.opaa.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link OidcProviderConnectionTester} (#1329) against a local HTTP double of an identity provider:
 * the discovery document must be reachable and name the configured issuer, and the JWK set
 * (discovered or overridden) must carry at least one key. Every failure has its own German message,
 * so a Systemverwaltung sees what to fix before saving; every address, including the discovered
 * {@code jwks_uri}, passes the address policy before it is fetched.
 */
class OidcProviderConnectionTesterTest {

  private HttpServer server;
  private String baseUrl;
  private String discoveryBody;
  private String jwksBody =
      "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"k1\",\"use\":\"sig\",\"n\":\"AQAB\",\"e\":\"AQAB\"}]}";
  private int discoveryStatus = 200;

  private OidcProviderConnectionTester tester;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    discoveryBody = discovery(baseUrl + "/realms/opaa", baseUrl + "/realms/opaa/certs");
    server.createContext(
        "/realms/opaa/.well-known/openid-configuration",
        exchange -> respond(exchange, discoveryStatus, discoveryBody));
    server.createContext("/realms/opaa/certs", exchange -> respond(exchange, 200, jwksBody));
    server.createContext("/other/certs", exchange -> respond(exchange, 200, jwksBody));
    server.createContext(
        "/redirecting/certs",
        exchange -> {
          exchange.getResponseHeaders().add("Location", baseUrl + "/other/certs");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.start();
    tester =
        new OidcProviderConnectionTester(
            new OidcDiscoveryClient(
                new OidcAddressPolicy(new TargetAddressValidator(true, List.of("127.0.0.1")))));
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  private static String discovery(String issuer, String jwksUri) {
    return "{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"" + jwksUri + "\"}";
  }

  @Test
  void aReachableIssuerWithKeysSucceeds() {
    OidcProviderConnectionTester.TestOutcome outcome = tester.test(baseUrl + "/realms/opaa", null);

    assertThat(outcome.success()).isTrue();
    assertThat(outcome.message()).contains("erreichbar").contains("1 Schlüssel");
  }

  @Test
  void theJwkSetOverrideIsUsedInsteadOfTheDiscoveredOne() {
    OidcProviderConnectionTester.TestOutcome outcome =
        tester.test(baseUrl + "/realms/opaa", baseUrl + "/other/certs");

    assertThat(outcome.success()).isTrue();
  }

  /**
   * The Compose split: the browser reaches the provider under the issuer, the backend only under
   * the override - the registry never fetches the issuer then, so the test must not fail on it.
   */
  @Test
  void anIssuerUnreachableFromTheBackendStillPassesThroughTheJwkSetOverride() {
    OidcProviderConnectionTester.TestOutcome outcome =
        tester.test("http://127.0.0.1:1/realms/opaa", baseUrl + "/realms/opaa/certs");

    assertThat(outcome.success()).isTrue();
    assertThat(outcome.message())
        .contains("erreichbar")
        .contains("vom Backend aus nicht geprüft")
        .contains("1 Schlüssel");
  }

  @Test
  void anIssuerMismatchInTheDiscoveryDocumentFails() {
    discoveryBody = discovery("https://elsewhere.example", baseUrl + "/realms/opaa/certs");

    OidcProviderConnectionTester.TestOutcome outcome = tester.test(baseUrl + "/realms/opaa", null);

    assertThat(outcome.success()).isFalse();
    assertThat(outcome.message()).contains("Issuer").contains("https://elsewhere.example");
  }

  @Test
  void anUnreachableIssuerFailsWithoutAStackTrace() {
    OidcProviderConnectionTester.TestOutcome outcome =
        tester.test("http://127.0.0.1:1/realms/opaa", null);

    assertThat(outcome.success()).isFalse();
    assertThat(outcome.message()).contains("nicht erreichbar");
  }

  @Test
  void aDiscoveryDocumentThatIsNotFoundFails() {
    discoveryStatus = 404;

    OidcProviderConnectionTester.TestOutcome outcome = tester.test(baseUrl + "/realms/opaa", null);

    assertThat(outcome.success()).isFalse();
    assertThat(outcome.message()).contains("404");
  }

  @Test
  void anEmptyJwkSetFails() {
    jwksBody = "{\"keys\":[]}";

    OidcProviderConnectionTester.TestOutcome outcome = tester.test(baseUrl + "/realms/opaa", null);

    assertThat(outcome.success()).isFalse();
    assertThat(outcome.message()).contains("Schlüssel");
  }

  @Test
  void aBlockedIssuerAddressFailsBeforeAnyRequestIsSent() {
    OidcProviderConnectionTester.TestOutcome outcome =
        tester.test("http://169.254.169.254/latest", null);

    assertThat(outcome.success()).isFalse();
    assertThat(outcome.message()).contains("OPAA_OIDC_TARGET_VALIDATION_ALLOWLIST");
  }

  @Test
  void aDiscoveredJwksAddressOutsideTheAllowlistIsRefused() {
    // the issuer is fine, but its discovery document points the key fetch elsewhere
    discoveryBody = discovery(baseUrl + "/realms/opaa", "http://169.254.169.254/latest/keys");

    OidcProviderConnectionTester.TestOutcome outcome = tester.test(baseUrl + "/realms/opaa", null);

    assertThat(outcome.success()).isFalse();
    assertThat(outcome.message())
        .contains("JWK-Set-URI")
        .contains("OPAA_OIDC_TARGET_VALIDATION_ALLOWLIST");
  }

  @Test
  void redirectsAreNeverFollowed() {
    OidcProviderConnectionTester.TestOutcome outcome =
        tester.test(baseUrl + "/realms/opaa", baseUrl + "/redirecting/certs");

    assertThat(outcome.success()).isFalse();
    assertThat(outcome.message()).contains("Weiterleitung");
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
