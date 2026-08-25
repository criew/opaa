package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.ScheduleFrequency;
import io.opaa.api.types.ScheduleWeekday;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * #485: {@link LibraryScheduleCodec} is the single place that turns the four UI intervalstufen into
 * a cron expression and back - every case here is a round trip (encode then decode reaches the same
 * shape again), the property {@link LibraryScheduleCodec#parse}'s own Javadoc relies on.
 */
class LibraryScheduleCodecTest {

  @Test
  void hourlyRoundTrips() {
    String cron = LibraryScheduleCodec.toCron(ScheduleFrequency.HOURLY, null, null, null);

    LibraryScheduleCodec.Schedule decoded = LibraryScheduleCodec.parse(cron);

    assertThat(decoded.frequency()).isEqualTo(ScheduleFrequency.HOURLY);
    assertThat(decoded.hour()).isNull();
    assertThat(decoded.minute()).isNull();
    assertThat(decoded.weekday()).isNull();
  }

  @Test
  void dailyRoundTrips() {
    String cron = LibraryScheduleCodec.toCron(ScheduleFrequency.DAILY, 3, 30, null);

    LibraryScheduleCodec.Schedule decoded = LibraryScheduleCodec.parse(cron);

    assertThat(decoded.frequency()).isEqualTo(ScheduleFrequency.DAILY);
    assertThat(decoded.hour()).isEqualTo(3);
    assertThat(decoded.minute()).isEqualTo(30);
    assertThat(decoded.weekday()).isNull();
  }

  @Test
  void weeklyRoundTrips() {
    String cron =
        LibraryScheduleCodec.toCron(ScheduleFrequency.WEEKLY, 6, 0, ScheduleWeekday.MONDAY);

    LibraryScheduleCodec.Schedule decoded = LibraryScheduleCodec.parse(cron);

    assertThat(decoded.frequency()).isEqualTo(ScheduleFrequency.WEEKLY);
    assertThat(decoded.hour()).isEqualTo(6);
    assertThat(decoded.minute()).isEqualTo(0);
    assertThat(decoded.weekday()).isEqualTo(ScheduleWeekday.MONDAY);
  }

  @Test
  void everyWeekdayRoundTrips() {
    for (ScheduleWeekday weekday : ScheduleWeekday.values()) {
      String cron = LibraryScheduleCodec.toCron(ScheduleFrequency.WEEKLY, 12, 15, weekday);

      assertThat(LibraryScheduleCodec.parse(cron).weekday()).isEqualTo(weekday);
    }
  }

  @Test
  void nullCronDecodesToDisabled() {
    LibraryScheduleCodec.Schedule decoded = LibraryScheduleCodec.parse(null);

    assertThat(decoded).isEqualTo(LibraryScheduleCodec.Schedule.DISABLED);
  }

  @Test
  void disabledNeverProducesACronExpression() {
    assertThatThrownBy(
            () -> LibraryScheduleCodec.toCron(ScheduleFrequency.DISABLED, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unrecognizedCronDegradesToDisabledInsteadOfThrowing() {
    LibraryScheduleCodec.Schedule decoded = LibraryScheduleCodec.parse("not a cron expression");

    assertThat(decoded).isEqualTo(LibraryScheduleCodec.Schedule.DISABLED);
  }

  @Test
  void nextRunAtIsNullForNoSchedule() {
    assertThat(LibraryScheduleCodec.nextRunAt(null, Instant.now(), ZoneOffset.UTC)).isNull();
  }

  @Test
  void nextRunAtForDailyScheduleIsTodayOrTomorrowAtTheConfiguredTime() {
    String cron = LibraryScheduleCodec.toCron(ScheduleFrequency.DAILY, 3, 0, null);
    Instant now = Instant.parse("2026-08-21T10:00:00Z");

    Instant next = LibraryScheduleCodec.nextRunAt(cron, now, ZoneOffset.UTC);

    assertThat(next).isEqualTo(Instant.parse("2026-08-22T03:00:00Z"));
  }

  @Test
  void nextRunAtForHourlyScheduleIsTheNextFullHour() {
    String cron = LibraryScheduleCodec.toCron(ScheduleFrequency.HOURLY, null, null, null);
    Instant now = Instant.parse("2026-08-21T10:15:00Z");

    Instant next = LibraryScheduleCodec.nextRunAt(cron, now, ZoneOffset.UTC);

    assertThat(next).isEqualTo(Instant.parse("2026-08-21T11:00:00Z"));
  }

  // PR #705 review, blocker 3: an undecodable/defective stored cron expression must not crash
  // GET/PUT /api/v1/libraries/{id} (KnowledgeLibraryService#toLibraryResponse) or the scheduler
  // tick (LibraryIndexingScheduler) - nextRunAt degrades to "never due" instead of throwing.
  @Test
  void nextRunAtDoesNotThrowForASyntacticallyMeaninglessStoredValue() {
    Instant next =
        LibraryScheduleCodec.nextRunAt("not a cron expression", Instant.now(), ZoneOffset.UTC);

    assertThat(next).isNull();
  }

  // Unlike the case above, this value passes parse()'s own field-shape check (six fields, "0"
  // first, wildcards in the day-of-month/month slots) - it can only be caught by CronExpression's
  // own range validation, exercised here directly against nextRunAt rather than through parse().
  @Test
  void nextRunAtDoesNotThrowForAValueThatPassesTheFieldShapeCheckButFailsRangeValidation() {
    Instant next = LibraryScheduleCodec.nextRunAt("0 0 99 * * *", Instant.now(), ZoneOffset.UTC);

    assertThat(next).isNull();
  }
}
