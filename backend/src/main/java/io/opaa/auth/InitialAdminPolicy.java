package io.opaa.auth;

import io.opaa.auth.oidc.OidcIssuerUris;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides whether a newly provisioned account is the initial system administrator. Since ADR-0033
 * (Entscheidung 5) the rule has exactly one remaining effect: in the {@code dev} mode the address
 * {@code opaa.auth.initial-admin-email} issued by the dev issuer becomes {@code SYSTEM_ADMIN}, so
 * {@code dev-admin} is the administrator of every development and test run (ADR-0005). A blank
 * address counts as unset there and falls back to the address of the configured default dev user -
 * an empty {@code OPAA_INITIAL_ADMIN_EMAIL} in the environment must not take the role from {@code
 * dev-admin}. In the {@code oidc} mode the rule grants nothing - not even through the default
 * provider: the first administrator is the local bootstrap account the seed creates, and provider
 * accounts become administrators by role assignment alone (manually or through a {@code
 * roles_claim}). Consulted only when an account is created; a matching address that is refused is
 * logged, the one trace an operator has when a first sign-in ended up without rights.
 */
@Component
public class InitialAdminPolicy {

  private static final Logger log = LoggerFactory.getLogger(InitialAdminPolicy.class);
  private static final String DEV_MODE = "dev";

  private final AuthProperties authProperties;

  public InitialAdminPolicy(AuthProperties authProperties) {
    this.authProperties = authProperties;
  }

  public boolean grantsSystemAdmin(String email, String issuer) {
    if (!DEV_MODE.equals(authProperties.mode())) {
      if (email != null && matchesConfiguredAddress(email, authProperties.initialAdminEmail())) {
        log.warn(
            "Account with the initial administrator address is created WITHOUT SYSTEM_ADMIN"
                + " (issuer: {}): since ADR-0033 the rule applies to the dev issuer only; in the"
                + " oidc mode the first administrator is the local bootstrap account and provider"
                + " accounts get the role by assignment.",
            issuer);
      }
      return false;
    }
    Optional<String> initialAdminEmail = devInitialAdminEmail();
    if (initialAdminEmail.isEmpty()
        || email == null
        || !matchesConfiguredAddress(email, initialAdminEmail.get())) {
      return false;
    }
    if (issuer != null
        && OidcIssuerUris.normalize(authProperties.dev().issuer())
            .equals(OidcIssuerUris.normalize(issuer))) {
      return true;
    }
    log.warn(
        "Account with the initial administrator address is created WITHOUT SYSTEM_ADMIN (issuer:"
            + " {}): in the dev mode only the dev issuer mints the initial administrator.",
        issuer);
    return false;
  }

  /** The configured address, or - blank - the default dev user's, so {@code dev-admin} stays. */
  private Optional<String> devInitialAdminEmail() {
    String configured = authProperties.initialAdminEmail();
    if (configured != null && !configured.isBlank()) {
      return Optional.of(configured);
    }
    AuthProperties.DevAuth dev = authProperties.dev();
    return dev.findUser(dev.defaultUser()).map(AuthProperties.DevUser::email);
  }

  private static boolean matchesConfiguredAddress(String email, String configured) {
    return configured != null
        && !configured.isBlank()
        && configured.trim().equalsIgnoreCase(email.trim());
  }
}
