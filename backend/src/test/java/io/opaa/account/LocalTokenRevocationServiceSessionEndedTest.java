package io.opaa.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.auth.LocalCredentialsRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Whether the session behind a request ended since its token was minted, read under the share lock
 * of the account's credentials row: by the session generation the token carries, or by the cutoff
 * for a token of the previous version without it.
 */
class LocalTokenRevocationServiceSessionEndedTest {

  private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

  private final LocalCredentialsRepository credentials = mock(LocalCredentialsRepository.class);
  private final LocalTokenRevocationService service =
      new LocalTokenRevocationService(
          mock(LocalRevokedTokenRepository.class),
          credentials,
          mock(LocalAuthProperties.class, invocation -> Duration.ofMinutes(15)),
          Clock.systemUTC());
  private final UUID userId = UUID.randomUUID();

  @Test
  void aSessionOfAnEarlierGenerationHasEnded() {
    stamp(4L, null);

    assertThat(service.sessionEndedSince(userId, session(3L, NOW))).isTrue();
    verify(credentials).lockSessionStamp(userId);
  }

  @Test
  void aSessionOfTheCurrentGenerationGoesOn() {
    stamp(4L, null);

    assertThat(service.sessionEndedSince(userId, session(4L, NOW))).isFalse();
  }

  @Test
  void aTokenWithoutGenerationIsHeldAgainstTheCutoff() {
    stamp(4L, Timestamp.from(NOW.plusSeconds(1)));

    assertThat(service.sessionEndedSince(userId, session(null, NOW))).isTrue();
    assertThat(service.sessionEndedSince(userId, session(null, NOW.plusSeconds(2)))).isFalse();
  }

  @Test
  void anAccountWithoutLocalCredentialsHasNoSessionToEnd() {
    when(credentials.lockSessionStamp(userId)).thenReturn(List.of());

    assertThat(service.sessionEndedSince(userId, session(1L, NOW))).isFalse();
  }

  private void stamp(long generation, Timestamp cutoff) {
    when(credentials.lockSessionStamp(userId))
        .thenReturn(List.<Object[]>of(new Object[] {generation, cutoff}));
  }

  private static Jwt session(Long generation, Instant issuedAt) {
    Jwt.Builder builder =
        Jwt.withTokenValue("t").header("alg", "none").subject("s").issuedAt(issuedAt);
    if (generation != null) {
      builder.claim(LocalAccessTokenService.SESSION_GENERATION_CLAIM, generation);
    }
    return builder.build();
  }
}
