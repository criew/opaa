package io.opaa.externalaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.common.TooManyRequestsException;
import io.opaa.externalaccess.ExternalAccessSettings.Values;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExternalAccessQuotaTest {

  private static final Instant START = Instant.parse("2026-09-18T08:00:00Z");

  private ExternalAccessSettingsService settings;
  private MovableClock clock;
  private ExternalAccessQuota quota;

  @BeforeEach
  void setUp() {
    settings = mock(ExternalAccessSettingsService.class);
    clock = new MovableClock(START);
    quota = new ExternalAccessQuota(settings, clock);
    limitPerHour(3);
  }

  private void limitPerHour(int limit) {
    when(settings.current())
        .thenReturn(
            new ExternalAccessSettingsService.View(
                new Values(true, 90, limit, List.of(), 600, ""), START, null));
  }

  @Test
  void aTokenOverItsQuotaIsRefusedClearlyRatherThanAnsweredSlowly() {
    UUID token = UUID.randomUUID();

    for (int i = 0; i < 3; i++) {
      quota.requireWithinQuota(token);
    }

    assertThatThrownBy(() -> quota.requireWithinQuota(token))
        .isInstanceOf(TooManyRequestsException.class)
        .hasMessageContaining("Kontingent")
        .satisfies(
            e -> assertThat(((TooManyRequestsException) e).retryAfterSeconds()).isPositive());
  }

  @Test
  void aSecondTokenOfTheSamePersonIsNotAffected() {
    UUID exhausted = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    for (int i = 0; i < 3; i++) {
      quota.requireWithinQuota(exhausted);
    }

    assertThatCode(() -> quota.requireWithinQuota(other)).doesNotThrowAnyException();
  }

  @Test
  void aSignedInPersonWithoutATokenPassesUntouched() {
    for (int i = 0; i < 100; i++) {
      quota.requireWithinQuota(null);
    }
    assertThatCode(() -> quota.requireWithinQuota(null)).doesNotThrowAnyException();
  }

  @Test
  void theWindowSlides() {
    UUID token = UUID.randomUUID();
    for (int i = 0; i < 3; i++) {
      quota.requireWithinQuota(token);
    }

    clock.advance(Duration.ofMinutes(61));

    assertThatCode(() -> quota.requireWithinQuota(token)).doesNotThrowAnyException();
  }

  @Test
  void aRaisedLimitTakesEffectWithoutARestart() {
    UUID token = UUID.randomUUID();
    for (int i = 0; i < 3; i++) {
      quota.requireWithinQuota(token);
    }
    limitPerHour(10);

    assertThatCode(() -> quota.requireWithinQuota(token)).doesNotThrowAnyException();
  }

  /** A clock a test moves by hand; {@link Clock#millis()} is all the quota reads. */
  private static final class MovableClock extends Clock {

    private Instant now;

    private MovableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
