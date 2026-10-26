package io.opaa.auth;

import io.opaa.common.ValidationException;
import io.opaa.security.TargetAddressValidator;
import java.io.IOException;
import java.net.URI;

/**
 * SSRF policy for every address the backend fetches for a provider (ADR-0025 Entscheidung 3): the
 * issuer, the JWK set override and the {@code jwks_uri} a discovery document names. Its {@link
 * TargetAddressValidator} is built from the anmeldeseitige block {@code
 * opaa.auth.oidc.target-validation} - deliberately not from the indexing one, so switching a
 * connector's check off never switches the sign-in check off. No address is exempt: an internal
 * provider, also the one of the installation, needs its host on the allowlist.
 *
 * <p>Applied at save time ({@code io.opaa.directory.OidcProviderService}), at use time ({@link
 * OidcProviderRegistry}, so a narrowed allowlist or a row edited directly in the database cannot
 * slip past), before the connection test sends anything ({@link OidcDiscoveryClient}) - and to the
 * admin API address of a provider's directory access, both when it is stored and every time a run
 * uses it ({@code DirectoryConnectorService} and {@code ProviderDirectoryClient} in the group
 * package).
 */
public class OidcAddressPolicy {

  static final String ALLOWLIST_HINT =
      "Interne Adressen gibt der Betrieb über OPAA_OIDC_TARGET_VALIDATION_ALLOWLIST frei.";

  private final TargetAddressValidator validator;

  public OidcAddressPolicy(TargetAddressValidator validator) {
    this.validator = validator;
  }

  /** The production policy: the configured allowlist. */
  public static OidcAddressPolicy fromProperties(AuthProperties.OidcAuth oidc) {
    return new OidcAddressPolicy(
        new TargetAddressValidator(
            oidc.targetValidation().enabled(), oidc.targetValidation().allowlist()));
  }

  /**
   * @param fieldLabel the German field name for the message ({@code "Issuer-URI"}, {@code
   *     "JWK-Set-URI"})
   * @throws ValidationException when {@code uri} is malformed or resolves into a blocked range
   */
  public void requireAllowed(String uri, String fieldLabel) {
    URI parsed = OidcIssuerUris.requireHttpUri(uri, fieldLabel);
    try {
      validator.validate(parsed);
    } catch (IOException e) {
      throw new ValidationException(fieldLabel + ": " + e.getMessage() + " " + ALLOWLIST_HINT);
    }
  }
}
