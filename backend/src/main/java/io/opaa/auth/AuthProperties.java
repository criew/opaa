package io.opaa.auth;

import java.util.List;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opaa.auth")
public record AuthProperties(String mode, OidcAuth oidc, DevAuth dev, String initialAdminEmail) {

  public AuthProperties {
    if (mode == null || mode.isBlank()) {
      mode = "";
    }
    if (oidc == null) {
      oidc = new OidcAuth(null, null);
    }
    if (dev == null) {
      dev = new DevAuth(null, null, null);
    }
  }

  public record OidcAuth(String authority, String clientId) {}

  /**
   * Configuration of the development authentication mode. There is no login and no token exchange:
   * {@link DevAuthFilter} authenticates every request as one of the configured users. Never
   * intended for anything but local development and automated tests.
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
