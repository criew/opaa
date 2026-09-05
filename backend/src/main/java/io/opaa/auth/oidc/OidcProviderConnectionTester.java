package io.opaa.auth.oidc;

import com.nimbusds.jose.jwk.JWKSet;

/**
 * Probes a provider before it is saved (#1329, the "Verbindungstest" of the Anbieterverwaltung):
 * the discovery document must be reachable and name the same issuer (OIDC Discovery requires the
 * two to match byte for byte - the most common misconfiguration is a trailing path or a host the
 * instance does not consider itself), and the JWK set (the override, if given, else the discovered
 * one) must carry at least one key. With an override, an issuer the backend cannot reach is no
 * failure: that is the Compose split the override exists for (the browser reaches the provider
 * under the issuer, the backend under the override), and the registry never fetches the issuer in
 * that case either - the outcome then says which of the two was verified. German outcomes, no stack
 * traces - the same shape as {@code LlmModelConnectionTester}; the fetching itself, including the
 * address policy, is {@link OidcDiscoveryClient}'s, so the test probes exactly what the registry
 * will later read.
 */
public class OidcProviderConnectionTester {

  private final OidcDiscoveryClient discoveryClient;

  public OidcProviderConnectionTester(OidcDiscoveryClient discoveryClient) {
    this.discoveryClient = discoveryClient;
  }

  /** German, user-facing outcome of one probe. */
  public record TestOutcome(boolean success, String message) {}

  public TestOutcome test(String issuerUri, String jwkSetUri) {
    String override = jwkSetUri == null || jwkSetUri.isBlank() ? null : jwkSetUri.trim();
    String jwksAddress;
    String discoveryNote = "Discovery-Dokument gefunden";
    try {
      OidcDiscoveryClient.Discovery discovery = discoveryClient.fetchDiscovery(issuerUri);
      jwksAddress = override == null ? discovery.jwksUri() : override;
    } catch (OidcDiscoveryClient.OidcProbeException e) {
      if (override == null) {
        return new TestOutcome(false, e.getMessage());
      }
      jwksAddress = override;
      discoveryNote =
          "Discovery-Dokument vom Backend aus nicht geprüft ("
              + e.getMessage()
              + "; im Betrieb liest das Backend nur die JWK-Set-Adresse)";
    }
    int keys;
    try {
      keys = JWKSet.parse(discoveryClient.fetchJwkSet(jwksAddress)).getKeys().size();
    } catch (OidcDiscoveryClient.OidcProbeException e) {
      return new TestOutcome(false, e.getMessage());
    } catch (Exception e) {
      return new TestOutcome(false, "Das JWK-Set ist nicht lesbar.");
    }
    if (keys == 0) {
      return new TestOutcome(false, "Das JWK-Set enthält keinen Schlüssel.");
    }
    return new TestOutcome(
        true,
        "Anbieter erreichbar: "
            + discoveryNote
            + ", JWK-Set mit "
            + keys
            + " Schlüssel"
            + (keys == 1 ? "" : "n")
            + ".");
  }
}
