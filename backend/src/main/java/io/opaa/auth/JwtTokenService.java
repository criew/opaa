package io.opaa.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;

public class JwtTokenService {

  private final SecretKey key;
  private final long expirationSeconds;
  private final String issuer;

  public JwtTokenService(String secret, long expirationSeconds, String issuer) {
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    this.expirationSeconds = expirationSeconds;
    this.issuer = issuer;
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
