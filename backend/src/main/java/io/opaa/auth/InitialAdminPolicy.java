package io.opaa.auth;

import io.opaa.auth.oidc.OidcIssuerUris;
import io.opaa.auth.oidc.OidcProviderRepository;
import org.springframework.stereotype.Component;

/**
 * Decides whether a newly provisioned account is the initial system administrator (ADR-0025,
 * Entscheidung 3; #1330): the address must match {@code opaa.auth.initial-admin-email},
 * <em>and</em> the account must come from the one provider the rule is bound to - in the {@code
 * oidc} mode the default provider, in the {@code dev} mode the dev issuer. A second provider whose
 * operator issues a token with the initial administrator's address therefore never grants {@code
 * SYSTEM_ADMIN}: the rule cannot be captured through another provider. Consulted only when an
 * account is created, exactly like the address-only rule it replaces.
 */
@Component
public class InitialAdminPolicy {

  private static final String OIDC_MODE = "oidc";
  private static final String DEV_MODE = "dev";

  private final AuthProperties authProperties;
  private final OidcProviderRepository providerRepository;

  public InitialAdminPolicy(
      AuthProperties authProperties, OidcProviderRepository providerRepository) {
    this.authProperties = authProperties;
    this.providerRepository = providerRepository;
  }

  public boolean grantsSystemAdmin(String email, String issuer) {
    String initialAdminEmail = authProperties.initialAdminEmail();
    if (initialAdminEmail == null
        || initialAdminEmail.isBlank()
        || email == null
        || !initialAdminEmail.equalsIgnoreCase(email)) {
      return false;
    }
    return issuedByTheTrustedProvider(issuer);
  }

  private boolean issuedByTheTrustedProvider(String issuer) {
    if (issuer == null) {
      return false;
    }
    String mode = authProperties.mode();
    if (DEV_MODE.equals(mode)) {
      return issuer.equals(authProperties.dev().issuer());
    }
    if (OIDC_MODE.equals(mode)) {
      String normalized = OidcIssuerUris.normalize(issuer);
      return providerRepository
          .findByDefaultProviderTrue()
          .map(provider -> provider.getIssuerUri().equals(normalized))
          .orElse(false);
    }
    return false;
  }
}
