package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/**
 * The reminder window is the calendar day fourteen days after the run's day in the run's zone -
 * across a daylight-saving change the day is 23 or 25 hours long, and two consecutive runs still
 * cover every instant exactly once.
 */
class ExpiryReminderStepTest {

  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

  @Test
  void theWindowIsTheCalendarDayFourteenDaysAheadInTheZoneOfTheRun() {
    Instant run = ZonedDateTime.of(2026, 10, 11, 3, 20, 0, 0, BERLIN).toInstant();

    ExpiryReminderStep.Window window = ExpiryReminderStep.windowOf(run, BERLIN);

    // 25 October 2026: the clocks go back, the day has 25 hours
    assertThat(window.from())
        .isEqualTo(LocalDate.of(2026, 10, 25).atStartOfDay(BERLIN).toInstant());
    assertThat(window.to()).isEqualTo(LocalDate.of(2026, 10, 26).atStartOfDay(BERLIN).toInstant());
    assertThat(Duration.between(window.from(), window.to())).isEqualTo(Duration.ofHours(25));
    assertThat(window.contains(window.from())).isTrue();
    assertThat(window.contains(window.to())).isFalse();
  }

  @Test
  void consecutiveRunsCoverEveryInstantExactlyOnceAcrossTheChange() {
    Instant dayBefore = ZonedDateTime.of(2026, 10, 10, 3, 20, 0, 0, BERLIN).toInstant();
    Instant day = ZonedDateTime.of(2026, 10, 11, 3, 20, 0, 0, BERLIN).toInstant();
    Instant dayAfter = ZonedDateTime.of(2026, 10, 12, 3, 20, 0, 0, BERLIN).toInstant();

    ExpiryReminderStep.Window first = ExpiryReminderStep.windowOf(dayBefore, BERLIN);
    ExpiryReminderStep.Window second = ExpiryReminderStep.windowOf(day, BERLIN);
    ExpiryReminderStep.Window third = ExpiryReminderStep.windowOf(dayAfter, BERLIN);

    assertThat(first.to()).isEqualTo(second.from());
    assertThat(second.to()).isEqualTo(third.from());
    Instant expiry = ZonedDateTime.of(2026, 10, 25, 2, 30, 0, 0, BERLIN).toInstant();
    assertThat(first.contains(expiry)).isFalse();
    assertThat(second.contains(expiry)).isTrue();
    assertThat(third.contains(expiry)).isFalse();
  }
}
