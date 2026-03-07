package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtUserClaimsTest {

  @Test
  void issuerReturnsUnknownWhenIssClaimMissing() {
    Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256").claim("sub", "admin").build();

    assertThat(JwtUserClaims.issuer(jwt)).isEqualTo("unknown");
  }

  @Test
  void displayNameFallsBackToPreferredUsernameWhenNameMissing() {
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "HS256")
            .claim("sub", "admin")
            .claim("preferred_username", "admin")
            .build();

    assertThat(JwtUserClaims.displayName(jwt)).isEqualTo("admin");
  }
}
