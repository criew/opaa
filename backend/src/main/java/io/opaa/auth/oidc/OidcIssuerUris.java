package io.opaa.auth.oidc;

import io.opaa.common.ValidationException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * The one place an issuer URI is normalized and checked for shape (#1329): trimmed, without a
 * trailing slash, and an absolute {@code http}/{@code https} URI. Tokens carry the issuer exactly
 * as the provider mints it and OIDC discovery requires the issuer to match byte for byte - so the
 * stored value must be the canonical one, and a lookup by a token's {@code iss} goes through the
 * same normalization.
 */
public final class OidcIssuerUris {

  private OidcIssuerUris() {}

  /** Trims and strips one trailing slash; {@code null} stays {@code null}. */
  public static String normalize(String issuerUri) {
    if (issuerUri == null) {
      return null;
    }
    String trimmed = issuerUri.trim();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed;
  }

  /**
   * @throws ValidationException when {@code uri} is blank, unparseable, relative or neither {@code
   *     http} nor {@code https}
   */
  public static URI requireHttpUri(String uri, String fieldLabel) {
    if (uri == null || uri.isBlank()) {
      throw new ValidationException(fieldLabel + " darf nicht leer sein.");
    }
    URI parsed;
    try {
      parsed = new URI(uri.trim());
    } catch (URISyntaxException e) {
      throw new ValidationException(fieldLabel + " ist keine gültige Adresse.");
    }
    String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
    if (!parsed.isAbsolute() || !(scheme.equals("http") || scheme.equals("https"))) {
      throw new ValidationException(
          fieldLabel + " muss eine absolute http- oder https-Adresse sein.");
    }
    if (parsed.getHost() == null || parsed.getHost().isBlank()) {
      throw new ValidationException(fieldLabel + " enthält keinen gültigen Host.");
    }
    return parsed;
  }
}
