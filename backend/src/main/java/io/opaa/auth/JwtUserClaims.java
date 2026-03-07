package io.opaa.auth;

import org.springframework.security.oauth2.jwt.Jwt;

final class JwtUserClaims {

  private static final String UNKNOWN_ISSUER = "unknown";

  private JwtUserClaims() {}

  static String issuer(Jwt jwt) {
    String issuer = jwt.getClaimAsString("iss");
    if (issuer == null || issuer.isBlank()) {
      return UNKNOWN_ISSUER;
    }
    return issuer;
  }

  static String displayName(Jwt jwt) {
    String displayName = jwt.getClaimAsString("name");
    if (displayName == null || displayName.isBlank()) {
      return jwt.getClaimAsString("preferred_username");
    }
    return displayName;
  }
}
