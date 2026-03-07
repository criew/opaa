package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class JwtTokenServiceTest {

  private static final String SECRET = "change-me-to-a-256-bit-secret-key-in-production!!";
  private static final String ISSUER = "opaa-basic";

  private final JwtTokenService tokenService = new JwtTokenService(SECRET, 3600, ISSUER);

  @Test
  void generateTokenContainsExpectedClaims() {
    String token = tokenService.generateToken("admin", "admin@opaa.local", "Admin User");

    Claims claims =
        Jwts.parser()
            .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
            .build()
            .parseSignedClaims(token)
            .getPayload();

    assertThat(claims.getSubject()).isEqualTo("admin");
    assertThat(claims.getIssuer()).isEqualTo(ISSUER);
    assertThat(claims.get("email", String.class)).isEqualTo("admin@opaa.local");
    assertThat(claims.get("name", String.class)).isEqualTo("Admin User");
    assertThat(claims.getExpiration()).isNotNull();
    assertThat(claims.getIssuedAt()).isNotNull();
  }

  @Test
  void getIssuerReturnsConfiguredIssuer() {
    assertThat(tokenService.getIssuer()).isEqualTo(ISSUER);
  }

  @Test
  void getExpirationSecondsReturnsConfiguredValue() {
    assertThat(tokenService.getExpirationSeconds()).isEqualTo(3600);
  }
}
