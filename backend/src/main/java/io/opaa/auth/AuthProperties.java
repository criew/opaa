package io.opaa.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opaa.auth")
public record AuthProperties(String mode, OidcAuth oidc, BasicAuth basic) {

  public AuthProperties {
    if (mode == null || mode.isBlank()) {
      mode = "mock";
    }
    if (oidc == null) {
      oidc = new OidcAuth(null, null);
    }
    if (basic == null) {
      basic = new BasicAuth(null, null, null, 0, null);
    }
  }

  public record OidcAuth(String authority, String clientId) {}

  public record BasicAuth(
      String username, String password, String secret, long tokenExpirationSeconds, String issuer) {

    public BasicAuth {
      if (tokenExpirationSeconds <= 0) {
        tokenExpirationSeconds = 3600;
      }
      if (issuer == null || issuer.isBlank()) {
        issuer = "opaa-basic";
      }
    }
  }
}
