package io.opaa.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.auth.LocalCredentialsRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Whether the session behind a request ended since its token was minted, read under the share lock
 * of the account's credentials row: by the session generation the token carries; a token without
 * one counts as ended.
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
    stamp(4L);

    assertThat(service.sessionEndedSince(userId, session(3L, NOW))).isTrue();
    verify(credentials).lockSessionGeneration(userId);
  }

  @Test
  void aSessionOfTheCurrentGenerationGoesOn() {
    stamp(4L);

    assertThat(service.sessionEndedSince(userId, session(4L, NOW))).isFalse();
  }

  @Test
  void aTokenWithoutGenerationHasEndedHoweverLateItsIat() {
    stamp(4L);

    assertThat(service.sessionEndedSince(userId, session(null, NOW.plusSeconds(60)))).isTrue();
  }

  @Test
  void anAccountWithoutLocalCredentialsHasNoSessionToEnd() {
    when(credentials.lockSessionGeneration(userId)).thenReturn(List.of());

    assertThat(service.sessionEndedSince(userId, session(1L, NOW))).isFalse();
  }

  private void stamp(long generation) {
    when(credentials.lockSessionGeneration(userId)).thenReturn(List.of(generation));
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
