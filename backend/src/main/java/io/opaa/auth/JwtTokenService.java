package io.opaa.auth;

import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public class JwtTokenService {

  private final SecretKey key;
  private final long expirationSeconds;
  private final String issuer;

  public JwtTokenService(String secret, long expirationSeconds, String issuer) {
    this.key = buildKey(secret);
    this.expirationSeconds = expirationSeconds;
    this.issuer = issuer;
  }

  /**
   * Builds the HS256 signing key from the given secret. Both token signing and JWT decoding must
   * use this method so that algorithm and key construction stay in sync.
   */
  public static SecretKey buildKey(String secret) {
    return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
  }

  public String generateToken(String subject, String email, String displayName) {
    Instant now = Instant.now();
    return Jwts.builder()
        .subject(subject)
        .issuer(issuer)
        .claim("email", email)
        .claim("name", displayName)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(expirationSeconds)))
        .signWith(key)
        .compact();
  }

  public long getExpirationSeconds() {
    return expirationSeconds;
  }

  public String getIssuer() {
    return issuer;
  }
}
