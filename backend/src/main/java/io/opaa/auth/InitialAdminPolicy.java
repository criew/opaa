package io.opaa.auth;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides whether a newly provisioned account is the initial system administrator. The rule has
 * exactly one effect: in the {@code dev} mode the address {@code opaa.auth.dev.system-admin-email}
 * issued by the dev issuer becomes {@code SYSTEM_ADMIN}, so {@code dev-admin} is the administrator
 * of every development and test run (ADR-0005); a blank address falls back to the default dev
 * user's. In the {@code oidc} mode the rule grants nothing: the first administrator is the local
 * account of the installation (ADR-0060, Entscheidung 8), and provider accounts become
 * administrators by role assignment alone. Consulted only when an account is created.
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
    AuthProperties.DevAuth dev = authProperties.dev();
    String configured = dev.systemAdminEmail();
    if (configured != null && !configured.isBlank()) {
      return Optional.of(configured);
    }
    return dev.findUser(dev.defaultUser()).map(AuthProperties.DevUser::email);
  }

  private static boolean matchesConfiguredAddress(String email, String configured) {
    return configured != null
        && !configured.isBlank()
        && configured.trim().equalsIgnoreCase(email.trim());
  }
}
