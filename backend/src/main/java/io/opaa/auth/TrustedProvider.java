package io.opaa.auth;

import io.opaa.auth.oidc.OidcIssuerUris;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one provider an installation trusts beyond signing in (ADR-0025, Entscheidung 3 and 4): the
 * enabled default provider in the {@code oidc} mode, the dev issuer in the {@code dev} mode. The
 * directory synchronisation resolves members among its accounts only. Empty while no such provider
 * exists - the callers decide what that means for them.
 */
@Component
public class TrustedProvider {

  private static final String OIDC_MODE = "oidc";
  private static final String DEV_MODE = "dev";

  private final AuthProperties authProperties;
  private final OidcProviderRepository providerRepository;

  public TrustedProvider(AuthProperties authProperties, OidcProviderRepository providerRepository) {
    this.authProperties = authProperties;
    this.providerRepository = providerRepository;
  }

  /**
   * The trusted provider's issuer exactly as its tokens carry it ({@code users.issuer}) - in the
   * {@code oidc} mode only while the default provider is enabled: a disabled row keeps {@code
   * is_default} (ADR-0033, Entscheidung 4), but is no anchor of trust, so the synchronisation
   * pauses instead of resolving members through it.
   */
  public Optional<String> issuer() {
    String mode = authProperties.mode();
    if (DEV_MODE.equals(mode)) {
      return Optional.ofNullable(authProperties.dev()).map(AuthProperties.DevAuth::issuer);
    }
    if (OIDC_MODE.equals(mode)) {
      return providerRepository
          .findByDefaultProviderTrueAndEnabledTrue()
          .map(OidcProvider::getIssuerUri);
    }
    return Optional.empty();
  }

  /**
   * The trusted provider's row id, which the groups of a directory synchronisation carry as their
   * origin ({@code groups.provider_id}, ADR-0036 Entscheidung 2). Empty in the {@code dev} mode,
   * where no provider row exists at all - whether the synchronisation runs there over a synthetic
   * row or not at all is decided by #1816.
   */
  public Optional<UUID> id() {
    if (!OIDC_MODE.equals(authProperties.mode())) {
      return Optional.empty();
    }
    return providerRepository.findByDefaultProviderTrueAndEnabledTrue().map(OidcProvider::getId);
  }

  /** Whether {@code issuer} names the trusted provider - compared without trailing slashes. */
  public boolean matches(String issuer) {
    if (issuer == null) {
      return false;
    }
    String normalized = OidcIssuerUris.normalize(issuer);
    return issuer()
        .map(trusted -> OidcIssuerUris.normalize(trusted).equals(normalized))
        .orElse(false);
  }
}
