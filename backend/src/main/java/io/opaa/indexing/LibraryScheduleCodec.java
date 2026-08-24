package io.opaa.indexing;

import io.opaa.api.dto.ScheduleFrequency;
import io.opaa.api.dto.ScheduleWeekday;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;

/**
 * Translates between the four fixed intervalstufen a library's indexing schedule may take
 * (stündlich/täglich HH:MM/wöchentlich am Tag X HH:MM/aus) and the cron expression {@link
 * io.opaa.library.KnowledgeLibrary#getScheduleCron()} stores - the single place that knows the
 * mapping, so a later "expert cron field" would only need a new UI, never a schema or codec change.
 * Every cron expression this class ever writes, it can also read back exactly (round-trip); {@link
 * #parse} only ever needs to cope with data this class itself wrote, since the API never accepts a
 * raw cron string.
 *
 * <p>Uses {@link CronExpression}'s six-field dialect (seconds first, matching {@code
 * io.opaa.audit.AuditRetentionScheduler}'s own {@code @Scheduled(cron = ...)} usage) - the second
 * field is always {@code "0"} here, since none of the four intervalstufen need sub-minute
 * precision.
 *
 * <p><b>#860 Teil 4 decision:</b> {@code ScheduleFrequency}/{@code ScheduleWeekday} stay generated
 * DTO types here rather than gaining a separate domain enum with an API-layer mapping. Unlike
 * {@code SourceReference} or {@code ChatSummary}, both enums carry no behaviour beyond their four
 * literal values, are identical in both layers by construction (the four intervalstufen this class
 * documents above), and Epic #826 Phase 4 already plans to move exactly this kind of small, shared,
 * behaviour-free enum into a future {@code opaa-api} module both the domain and the API layer
 * depend on - a domain-local duplicate today would just be undone there.
 */
public final class LibraryScheduleCodec {

  private static final Logger log = LoggerFactory.getLogger(LibraryScheduleCodec.class);

  private LibraryScheduleCodec() {}

  /** The decoded shape of a stored cron expression, or the "no schedule" case. */
  public record Schedule(
      ScheduleFrequency frequency, Integer hour, Integer minute, ScheduleWeekday weekday) {

    public static final Schedule DISABLED =
        new Schedule(ScheduleFrequency.DISABLED, null, null, null);
  }

  /**
   * Builds the cron expression for an already-validated schedule ({@code
   * io.opaa.library.KnowledgeLibraryService#validateSchedule} enforces which of hour/minute/
   * weekday are required for each frequency before this is ever called). Never called for {@link
   * ScheduleFrequency#DISABLED} - a disabled schedule stores no cron at all ({@code
   * chk_knowledge_libraries_schedule}).
   */
  public static String toCron(
      ScheduleFrequency frequency, Integer hour, Integer minute, ScheduleWeekday weekday) {
    return switch (frequency) {
      case HOURLY -> "0 0 * * * *";
      case DAILY -> "0 " + minute + " " + hour + " * * *";
      case WEEKLY -> "0 " + minute + " " + hour + " * * " + toCronWeekday(weekday);
      case DISABLED ->
          throw new IllegalArgumentException("DISABLED never stores a cron expression");
    };
  }

  /**
   * Decodes a stored cron expression back into the four-intervalstufen shape the UI/API works with.
   * {@code null} decodes to {@link Schedule#DISABLED}, matching the stored pair ({@code
   * schedule_cron} is {@code null} exactly when {@code schedule_enabled} is {@code false}).
   *
   * <p>An unrecognized shape logs a warning and falls back to {@link Schedule#DISABLED} rather than
   * throwing, mirroring {@code SourceCredentialsConverter}'s "cannot decode, degrade visibly, do
   * not fail the whole load" reasoning - this can only happen for a row this class did not itself
   * write (a hand-edited database row, say).
   */
  public static Schedule parse(String cron) {
    if (cron == null) {
      return Schedule.DISABLED;
    }
    String[] fields = cron.trim().split("\\s+");
    if (fields.length != 6
        || !"0".equals(fields[0])
        || !"*".equals(fields[3])
        || !"*".equals(fields[4])) {
      return unrecognized(cron);
    }
    String minuteField = fields[1];
    String hourField = fields[2];
    String weekdayField = fields[5];
    if ("0".equals(minuteField) && "*".equals(hourField) && "*".equals(weekdayField)) {
      return new Schedule(ScheduleFrequency.HOURLY, null, null, null);
    }
    Integer minute = parseIntOrNull(minuteField);
    Integer hour = parseIntOrNull(hourField);
    if (minute == null || hour == null) {
      return unrecognized(cron);
    }
    if ("*".equals(weekdayField)) {
      return new Schedule(ScheduleFrequency.DAILY, hour, minute, null);
    }
    ScheduleWeekday weekday = fromCronWeekday(weekdayField);
    if (weekday == null) {
      return unrecognized(cron);
    }
    return new Schedule(ScheduleFrequency.WEEKLY, hour, minute, weekday);
  }

  /**
   * The next time {@code cron} fires strictly after {@code now}, at {@code zone} - {@code null}
   * when {@code cron} is itself {@code null} (no schedule). Used both for {@code
   * LibraryResponse.schedule.nextRunAt} and, at minute granularity, by {@code
   * LibraryIndexingScheduler} to decide whether a library is due on the current tick.
   *
   * <p>{@link CronExpression#parse} throws {@link IllegalArgumentException} for a string that does
   * not parse as a valid six-field cron expression - a case {@link #parse} above cannot always rule
   * out on its own, since a value can pass that method's field-shape check while still failing
   * {@link CronExpression}'s own range validation (an hour of 99, say). Without this try/catch, an
   * undecodable stored value would propagate out of every caller, mirroring {@link #parse}'s own
   * "cannot decode, degrade visibly, do not fail the whole load" reasoning. Degrading here means
   * treating the schedule as never due / with no next run, not {@link Schedule#DISABLED} outright.
   */
  public static Instant nextRunAt(String cron, Instant now, ZoneId zone) {
    if (cron == null) {
      return null;
    }
    try {
      ZonedDateTime next = CronExpression.parse(cron).next(ZonedDateTime.ofInstant(now, zone));
      return next == null ? null : next.toInstant();
    } catch (IllegalArgumentException e) {
      log.warn(
          "Could not evaluate stored library schedule cron expression '{}', treating it as never"
              + " due",
          cron,
          e);
      return null;
    }
  }

  private static Schedule unrecognized(String cron) {
    log.warn(
        "Could not decode stored library schedule cron expression '{}', treating as disabled",
        cron);
    return Schedule.DISABLED;
  }

  private static Integer parseIntOrNull(String value) {
    try {
      return Integer.valueOf(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String toCronWeekday(ScheduleWeekday weekday) {
    return DayOfWeek.valueOf(weekday.name()).name().substring(0, 3).toUpperCase(Locale.ROOT);
  }

  private static ScheduleWeekday fromCronWeekday(String field) {
    for (ScheduleWeekday weekday : ScheduleWeekday.values()) {
      if (weekday.name().substring(0, 3).equalsIgnoreCase(field)) {
        return weekday;
      }
    }
    return null;
  }
}
