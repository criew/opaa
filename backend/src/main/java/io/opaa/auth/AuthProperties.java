package io.opaa.auth;

import io.opaa.compliance.InstallationWide;
import io.opaa.compliance.NoDataImpact;
import io.opaa.compliance.PerInstance;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How OPAA authenticates (ADR-0005).
 *
 * @param mode {@code oidc} or {@code dev}; set by the active profile, empty refuses the start
 * @param oidc the address check of identity providers in the {@code oidc} mode
 * @param dev the users of the development mode
 */
@PerInstance("development values of this process")
@NoDataImpact("authentication mode and address check of identity providers")
@ConfigurationProperties(prefix = "opaa.auth")
public record AuthProperties(@InstallationWide String mode, OidcAuth oidc, DevAuth dev) {

  public AuthProperties {
    if (mode == null || mode.isBlank()) {
      mode = "";
    }
    if (oidc == null) {
      oidc = new OidcAuth(null);
    }
    if (dev == null) {
      dev = new DevAuth(null, null, null, null, null, null);
    }
  }

  /**
   * The {@code oidc} block: the anmeldeseitige SSRF allowlist of every issuer and JWK set address,
   * deliberately separate from the indexing one. The providers themselves come from the
   * installation and the Anbieterverwaltung (ADR-0060, Entscheidung 9).
   *
   * @param targetValidation the address check of identity providers
   */
  @InstallationWide
  public record OidcAuth(TargetValidation targetValidation) {

    public OidcAuth {
      if (targetValidation == null) {
        targetValidation = new TargetValidation(true, List.of());
      }
    }
  }

  /**
   * {@code opaa.auth.oidc.target-validation} - see {@link OidcAuth}. {@code enabled} is a boxed
   * {@link Boolean} so an operator who sets only the allowlist does not silently switch the check
   * off (a missing primitive would bind as {@code false}); {@code null} means enabled.
   *
   * @param enabled whether addresses of identity providers are checked against internal networks
   * @param allowlist hosts of identity providers that may resolve to internal addresses
   */
  @InstallationWide
  public record TargetValidation(
      @NoDataImpact("SSRF hardening of identity provider addresses") Boolean enabled,
      List<String> allowlist) {

    public TargetValidation {
      if (enabled == null) {
        enabled = true;
      }
      if (allowlist == null) {
        allowlist = List.of();
      }
    }
  }

  /**
   * Configuration of the development authentication mode. There is no login and no token exchange:
   * {@link DevAuthFilter} authenticates every request as one of the configured users. Never
   * intended for anything but local development and automated tests.
   *
   * @param systemAdminEmail the address of the development user that becomes system administrator
   *     on its first request; empty means the default user's
   * @param issuer the issuer recorded for development users
   * @param defaultUser the subject a request without {@code X-OPAA-Dev-User} is authenticated as
   * @param users the development users
   * @param publishedAddress the host address the Compose stack publishes the frontend on, empty
   *     when the backend does not run in that stack; anything but a loopback address refuses the
   *     start unless {@code allowRemote} is set ({@link DevExposureGuard})
   * @param allowRemote whether the development mode may run with a frontend published beyond the
   *     loopback interface, where everyone reaching the port acts as system administrator
   */
  public record DevAuth(
      String systemAdminEmail,
      String issuer,
      String defaultUser,
      List<DevUser> users,
      String publishedAddress,
      @NoDataImpact("start guard of the development mode") Boolean allowRemote) {

    public DevAuth {
      if (publishedAddress == null) {
        publishedAddress = "";
      }
      if (allowRemote == null) {
        allowRemote = false;
      }
      if (issuer == null || issuer.isBlank()) {
        issuer = "opaa-dev";
      }
      if (users == null || users.isEmpty()) {
        users = List.of(new DevUser("dev-user", null, null));
      }
      if (defaultUser == null || defaultUser.isBlank()) {
        defaultUser = users.getFirst().subject();
      }
    }

    /** Returns the configured user with the given subject, or empty if there is no such user. */
    public Optional<DevUser> findUser(String subject) {
      return users.stream().filter(user -> user.subject().equals(subject)).findFirst();
    }
  }

  public record DevUser(String subject, String email, String displayName) {

    public DevUser {
      if (email == null || email.isBlank()) {
        email = subject + "@opaa.local";
      }
      if (displayName == null || displayName.isBlank()) {
        displayName = subject;
      }
    }
  }
}
