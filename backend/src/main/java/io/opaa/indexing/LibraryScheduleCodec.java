package io.opaa.indexing;

import io.opaa.api.types.ScheduleFrequency;
import io.opaa.api.types.ScheduleWeekday;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;

/**
 * Translates between the four fixed intervalstufen a library's indexing schedule may take and the
 * cron expression {@link io.opaa.library.KnowledgeLibrary#getScheduleCron()} stores - the single
 * place that knows the mapping. Every expression this class writes it can also read back exactly,
 * and {@link #parse} only ever meets data this class itself wrote, since the API never accepts a
 * raw cron string.
 *
 * <p>Uses {@link CronExpression}'s six-field dialect (seconds first); the second field is always
 * {@code "0"}, as none of the four intervalstufen need sub-minute precision.
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
   * Decodes a stored cron expression back into the four-intervalstufen shape the API works with;
   * {@code null} decodes to {@link Schedule#DISABLED}, matching the stored pair. An unrecognized
   * shape logs a warning and falls back to {@link Schedule#DISABLED} rather than throwing - it can
   * only occur for a row this class did not write, and must not fail the whole load.
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
   * The next time {@code cron} fires strictly after {@code now}, at {@code zone}, or {@code null}
   * without a schedule. {@link CronExpression#parse} throws for a value that passes {@link
   * #parse}'s field-shape check but fails its own range validation, so that is caught here: an
   * undecodable value degrades to "never due / no next run" rather than propagating out of every
   * caller.
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
