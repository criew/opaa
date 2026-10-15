package io.opaa.auth;

import io.opaa.compliance.NoDataImpact;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How OPAA authenticates (ADR-0005).
 *
 * @param mode {@code oidc} or {@code dev}; set by the active profile, empty refuses the start
 * @param oidc the bootstrap of the first identity provider in the {@code oidc} mode
 * @param dev the users of the development mode
 * @param initialAdminEmail the address that becomes system administrator on its first sign-in and
 *     of the local bootstrap administrator
 */
@NoDataImpact("authentication mode and bootstrap of the first identity provider")
@ConfigurationProperties(prefix = "opaa.auth")
public record AuthProperties(String mode, OidcAuth oidc, DevAuth dev, String initialAdminEmail) {

  public AuthProperties {
    if (mode == null || mode.isBlank()) {
      mode = "";
    }
    if (oidc == null) {
      oidc = new OidcAuth(null, null, null, null, null, null);
    }
    if (dev == null) {
      dev = new DevAuth(null, null, null);
    }
  }

  /**
   * The bootstrap block of the {@code oidc} mode (ADR-0025, Entscheidung 3): read once by {@code
   * io.opaa.auth.OidcProviderSeeder} to create the first provider row, and by the address policy
   * for the hosts that are always allowed. {@code bootstrap = "force"} makes the seeder ignore its
   * marker once and restore the environment provider; {@code targetValidation} is the
   * anmeldeseitige SSRF allowlist, deliberately separate from the indexing one.
   *
   * @param authority the authority of the sign-in flow; without effect besides the issuer
   * @param clientId the client id of the first provider
   * @param issuerUri the issuer of the first provider
   * @param jwkSetUri the key set of the first provider, when it is not discovered from the issuer
   * @param bootstrap {@code force} restores the environment provider once at start-up
   */
  public record OidcAuth(
      String authority,
      String clientId,
      @NoDataImpact("read once to seed the first provider; no default") String issuerUri,
      @NoDataImpact("read once to seed the first provider; no default") String jwkSetUri,
      String bootstrap,
      TargetValidation targetValidation) {

    public static final String BOOTSTRAP_FORCE = "force";

    public OidcAuth {
      if (targetValidation == null) {
        targetValidation = new TargetValidation(true, List.of());
      }
    }

    public boolean isBootstrapForced() {
      return BOOTSTRAP_FORCE.equalsIgnoreCase(bootstrap == null ? "" : bootstrap.trim());
    }
  }

  /**
   * {@code opaa.auth.oidc.target-validation} - see {@link OidcAuth}. {@code enabled} is a boxed
   * {@link Boolean} so an operator who sets only the allowlist does not silently switch the check
   * off (a missing primitive would bind as {@code false}); {@code null} means enabled.
   *
   * @param enabled whether addresses of identity providers are checked against internal networks
   * @param allowlist hosts of further identity providers that may resolve to internal addresses
   */
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
   * @param issuer the issuer recorded for development users
   * @param defaultUser the subject a request without {@code X-OPAA-Dev-User} is authenticated as
   * @param users the development users
   */
  public record DevAuth(String issuer, String defaultUser, List<DevUser> users) {

    public DevAuth {
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
