package io.opaa.auth.oidc;

import io.opaa.common.ValidationException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fetches a provider's discovery document and JWK set with the address policy applied before every
 * request and no redirects followed at all (#1329, ADR-0025 Entscheidung 3): the {@code jwks_uri} a
 * discovery document names is operator-independent content of the provider and must pass the same
 * check as the issuer the operator typed, before the first byte is read from it. Shared by the
 * connection test and the decoder factory, so both see exactly the same provider.
 */
public class OidcDiscoveryClient {

  private static final Logger log = LoggerFactory.getLogger(OidcDiscoveryClient.class);

  /** Shared with the JWK set client of {@link NimbusOidcJwtDecoderFactory}. */
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private static final int MAX_BODY_BYTES = 256 * 1024;
  static final String DISCOVERY_PATH = "/.well-known/openid-configuration";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** What a discovery document says about itself; {@code jwksUri} is already policy-checked. */
  public record Discovery(String issuer, String jwksUri) {}

  /**
   * A failed probe, with a German message a Systemverwaltung can act on. {@code unreachable} tells
   * "the backend could not reach the address at all" (timeout, connection refused, unknown host)
   * apart from an answer that was reached and rejected (status, redirect, malformed document,
   * issuer mismatch, address policy) - only the former is what a JWK set override may stand in for.
   */
  public static final class OidcProbeException extends Exception {
    private final boolean unreachable;

    OidcProbeException(String message) {
      this(message, false);
    }

    OidcProbeException(String message, boolean unreachable) {
      super(message);
      this.unreachable = unreachable;
    }

    public boolean isUnreachable() {
      return unreachable;
    }
  }

  private final OidcAddressPolicy addressPolicy;
  private final HttpClient httpClient;

  public OidcDiscoveryClient(OidcAddressPolicy addressPolicy) {
    this.addressPolicy = addressPolicy;
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  /**
   * The discovery document under {@code issuerUri}, checked to name the same issuer and a {@code
   * jwks_uri} the address policy allows.
   */
  public Discovery fetchDiscovery(String issuerUri) throws OidcProbeException {
    String issuer = OidcIssuerUris.normalize(issuerUri);
    requireAllowed(issuer, "Issuer-URI");
    JsonNode document;
    try {
      document = JSON.readTree(fetch(issuer + DISCOVERY_PATH));
    } catch (OidcProbeException e) {
      throw new OidcProbeException("Discovery-Dokument: " + e.getMessage(), e.isUnreachable());
    } catch (RuntimeException e) {
      throw new OidcProbeException("Das Discovery-Dokument ist kein gültiges JSON.");
    }
    String announced = OidcIssuerUris.normalize(document.path("issuer").asString(null));
    if (announced == null || !announced.equals(issuer)) {
      throw new OidcProbeException(
          "Der Issuer im Discovery-Dokument („"
              + announced
              + "“) stimmt nicht mit der eingegebenen Issuer-URI überein.");
    }
    String jwksUri = document.path("jwks_uri").asString(null);
    if (jwksUri == null || jwksUri.isBlank()) {
      throw new OidcProbeException("Das Discovery-Dokument nennt keine JWK-Set-Adresse.");
    }
    requireAllowed(jwksUri, "JWK-Set-URI");
    return new Discovery(announced, jwksUri);
  }

  /** The raw JWK set document at {@code jwkSetUri}, after the address policy. */
  public String fetchJwkSet(String jwkSetUri) throws OidcProbeException {
    requireAllowed(jwkSetUri, "JWK-Set-URI");
    try {
      return fetch(jwkSetUri.trim());
    } catch (OidcProbeException e) {
      throw new OidcProbeException("JWK-Set: " + e.getMessage(), e.isUnreachable());
    }
  }

  private void requireAllowed(String uri, String fieldLabel) throws OidcProbeException {
    try {
      addressPolicy.requireAllowed(uri, fieldLabel);
    } catch (ValidationException e) {
      throw new OidcProbeException(e.getMessage());
    }
  }

  /**
   * One request, no redirect followed: a redirect answer is a failure, never a second address the
   * policy has not seen. The address itself already passed {@link OidcAddressPolicy}.
   */
  private String fetch(String url) throws OidcProbeException {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(REQUEST_TIMEOUT)
              .header("Accept", "application/json")
              .GET()
              .build();
      HttpResponse<InputStream> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = response.body()) {
        String text = new String(body.readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
        if (response.statusCode() >= 300 && response.statusCode() < 400) {
          throw new OidcProbeException("Weiterleitungen werden nicht gefolgt.");
        }
        if (response.statusCode() != 200) {
          throw new OidcProbeException("Antwort mit HTTP " + response.statusCode() + ".");
        }
        return text;
      }
    } catch (OidcProbeException e) {
      throw e;
    } catch (HttpTimeoutException e) {
      throw new OidcProbeException("Der Anbieter hat nicht rechtzeitig geantwortet.", true);
    } catch (ConnectException | UnknownHostException e) {
      throw new OidcProbeException("Der Anbieter ist nicht erreichbar.", true);
    } catch (IOException e) {
      log.info("OIDC probe of {} failed: {}", url, e.getMessage());
      throw new OidcProbeException(
          "Der Anbieter ist nicht erreichbar (" + e.getMessage() + ").", true);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OidcProbeException("Der Abruf wurde unterbrochen.");
    } catch (IllegalArgumentException e) {
      throw new OidcProbeException("Die Adresse ist ungültig.");
    }
  }
}
