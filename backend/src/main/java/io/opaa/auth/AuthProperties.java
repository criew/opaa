package io.opaa.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opaa.auth")
public record AuthProperties(BasicAuth basic, String initialAdminEmail) {

  public AuthProperties {
    if (basic == null) {
      basic = new BasicAuth(null, null, null, 0, null);
    }
  }

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
