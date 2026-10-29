package io.opaa.test;

import io.opaa.account.LocalAccessTokenService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The web session a test issues an access token from: a token of a fresh sign-in of {@code userId},
 * carrying the account's current session generation when it has local credentials - the shape the
 * issuance holds against an ended session.
 */
public final class TestSessions {

  private TestSessions() {}

  public static Jwt current(JdbcTemplate jdbc, UUID userId) {
    Instant now = Instant.now();
    Jwt.Builder session =
        Jwt.withTokenValue("test-session")
            .header("alg", "HS256")
            .subject(userId.toString())
            .issuedAt(now)
            .expiresAt(now.plus(Duration.ofMinutes(15)));
    List<Long> generation =
        jdbc.queryForList(
            "SELECT session_generation FROM local_credentials WHERE user_id = ?",
            Long.class,
            userId);
    if (!generation.isEmpty()) {
      session.claim(LocalAccessTokenService.SESSION_GENERATION_CLAIM, generation.getFirst());
    }
    return session.build();
  }
}
