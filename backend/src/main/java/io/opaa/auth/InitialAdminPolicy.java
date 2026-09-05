package io.opaa.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides whether a newly provisioned account is the initial system administrator (ADR-0025,
 * Entscheidung 3): the address must match {@code opaa.auth.initial-admin-email} <em>and</em> the
 * account must come from the {@link TrustedProvider}, so a second provider's operator can never
 * mint one. Consulted only when an account is created. A matching address that is refused is
 * logged: it is the one trace an operator has when the first sign-in ended up without rights.
 */
@Component
public class InitialAdminPolicy {

  private static final Logger log = LoggerFactory.getLogger(InitialAdminPolicy.class);

  private final AuthProperties authProperties;
  private final TrustedProvider trustedProvider;

  public InitialAdminPolicy(AuthProperties authProperties, TrustedProvider trustedProvider) {
    this.authProperties = authProperties;
    this.trustedProvider = trustedProvider;
  }

  public boolean grantsSystemAdmin(String email, String issuer) {
    String initialAdminEmail = authProperties.initialAdminEmail();
    if (initialAdminEmail == null
        || initialAdminEmail.isBlank()
        || email == null
        || !initialAdminEmail.equalsIgnoreCase(email)) {
      return false;
    }
    if (trustedProvider.matches(issuer)) {
      return true;
    }
    log.warn(
        "Account with the initial administrator address is created WITHOUT SYSTEM_ADMIN: {}"
            + " (issuer: {}). The rule applies only to the trusted provider.",
        trustedProvider.issuer().isPresent()
            ? "not the trusted provider's issuer"
            : "no trusted provider exists yet (auth mode '" + authProperties.mode() + "')",
        issuer);
    return false;
  }
}
