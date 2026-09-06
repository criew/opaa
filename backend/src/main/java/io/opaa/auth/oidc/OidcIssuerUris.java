package io.opaa.auth.oidc;

import io.opaa.common.ValidationException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * The one place an issuer URI is normalized and checked for shape (ADR-0025, Entscheidung 2). A
 * provider's issuer is <em>stored</em> exactly as the provider mints it (trimmed, trailing slash
 * included - Auth0 mints {@code iss} with one), because a token's {@code iss} is compared with it
 * byte for byte and {@code users.issuer} carries the same minted value. {@link #normalize} is the
 * <em>lookup key</em> only: the registry map, the uniqueness rule ({@code
 * ux_oidc_providers_issuer_uri_normalized}, the SQL twin of this method) and every "is this the
 * default provider's issuer?" comparison go through it, so two rows that differ by a trailing slash
 * cannot coexist and a lookup by either spelling finds the row.
 */
public final class OidcIssuerUris {

  private OidcIssuerUris() {}

  /**
   * Trims and strips every trailing slash - the lookup key, never the stored value; {@code null}
   * stays {@code null}.
   */
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
