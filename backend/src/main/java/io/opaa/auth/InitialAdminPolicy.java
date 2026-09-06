package io.opaa.auth;

import io.opaa.auth.oidc.OidcIssuerUris;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides whether a newly provisioned account is the initial system administrator (ADR-0025,
 * Entscheidung 3): the address must match {@code opaa.auth.initial-admin-email} <em>and</em> the
 * account must come from the one trusted provider - the default provider in the {@code oidc} mode,
 * the dev issuer in the {@code dev} mode - so a second provider's operator can never mint one.
 * Consulted only when an account is created. A matching address that is refused is logged: it is
 * the one trace an operator has when the first sign-in ended up without rights.
 */
@Component
public class InitialAdminPolicy {

  private static final Logger log = LoggerFactory.getLogger(InitialAdminPolicy.class);

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
    String refusal = refusalOf(issuer);
    if (refusal == null) {
      return true;
    }
    log.warn(
        "Account with the initial administrator address is created WITHOUT SYSTEM_ADMIN: {}"
            + " (issuer: {}). The rule applies only to the trusted provider.",
        refusal,
        issuer);
    return false;
  }

  /** {@code null} when {@code issuer} is the trusted provider, otherwise the reason it is not. */
  private String refusalOf(String issuer) {
    if (issuer == null) {
      return "the token names no issuer";
    }
    String mode = authProperties.mode();
    if (DEV_MODE.equals(mode)) {
      return issuer.equals(authProperties.dev().issuer()) ? null : "not the dev issuer";
    }
    if (!OIDC_MODE.equals(mode)) {
      return "auth mode '" + mode + "' knows no trusted provider";
    }
    Optional<OidcProvider> standard = providerRepository.findByDefaultProviderTrue();
    if (standard.isEmpty()) {
      return "no default provider exists yet";
    }
    String normalized = OidcIssuerUris.normalize(issuer);
    return OidcIssuerUris.normalize(standard.get().getIssuerUri()).equals(normalized)
        ? null
        : "not the default provider's issuer";
  }
}
