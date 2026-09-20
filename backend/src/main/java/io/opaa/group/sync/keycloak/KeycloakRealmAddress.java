package io.opaa.group.sync.keycloak;

import io.opaa.common.ValidationException;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Where a Keycloak directory run reads, derived from the provider's own issuer URI (#1817). A
 * Keycloak issuer is always {@code <base>/realms/<realm>}, so both halves are already in the one
 * value the sign-in verifies tokens against - and deriving them is what makes it impossible to
 * point the run at a different realm than the one the accounts come from (ADR-0025: an account of
 * provider B must never receive the group memberships of the same-named subject of provider A).
 *
 * @param baseUrl the admin API base without a trailing slash, e.g. {@code http://keycloak:8180} -
 *     the operator's override when one is stored, otherwise the issuer minus its {@code
 *     /realms/<realm>} suffix (the legacy {@code /auth} prefix survives that).
 * @param realm the realm segment of the issuer, always taken from the issuer even when {@code
 *     baseUrl} was overridden
 */
public record KeycloakRealmAddress(String baseUrl, String realm) {

  private static final String REALMS_SEGMENT = "/realms/";

  /**
   * @param issuerUri the provider's issuer as stored
   * @param baseUrlOverride the backend-side admin API address, or null/blank to derive it
   * @throws ValidationException when the issuer is not a Keycloak realm address, or the override is
   *     not an absolute http(s) URI - both are operator input and get a German message
   */
  public static KeycloakRealmAddress of(String issuerUri, String baseUrlOverride) {
    String issuer = trimTrailingSlash(issuerUri == null ? "" : issuerUri.trim());
    int index = issuer.lastIndexOf(REALMS_SEGMENT);
    if (index < 0 || index + REALMS_SEGMENT.length() >= issuer.length()) {
      throw new ValidationException(
          "Die Issuer-URI dieses Anbieters ist keine Keycloak-Realm-Adresse (erwartet wird"
              + " „…/realms/<Realm>“). Für ein anderes Verzeichnis ist ein eigener Konnektor"
              + " nötig.");
    }
    String realm = issuer.substring(index + REALMS_SEGMENT.length());
    if (realm.contains("/")) {
      throw new ValidationException(
          "Die Issuer-URI dieses Anbieters nennt hinter „/realms/“ keinen einzelnen Realm.");
    }
    String derivedBase = issuer.substring(0, index);
    String base =
        baseUrlOverride == null || baseUrlOverride.isBlank()
            ? derivedBase
            : trimTrailingSlash(baseUrlOverride.trim());
    requireHttpUri(base);
    return new KeycloakRealmAddress(base, decode(realm));
  }

  private static void requireHttpUri(String value) {
    URI parsed;
    try {
      parsed = new URI(value);
    } catch (URISyntaxException e) {
      throw new ValidationException("Die Admin-API-Adresse ist keine gültige Adresse.");
    }
    String scheme = parsed.getScheme();
    if (parsed.getHost() == null
        || scheme == null
        || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      throw new ValidationException(
          "Die Admin-API-Adresse muss eine absolute http(s)-Adresse sein, z. B."
              + " „http://keycloak:8180“.");
    }
  }

  /**
   * A realm name may contain characters the issuer carries percent-encoded; every admin API path
   * below is built from the decoded name and re-encoded per segment, so both halves stay
   * consistent.
   */
  private static String decode(String realm) {
    return java.net.URLDecoder.decode(realm, java.nio.charset.StandardCharsets.UTF_8);
  }

  private static String trimTrailingSlash(String value) {
    String result = value;
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }
}
