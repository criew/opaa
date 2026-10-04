package io.opaa.library;

import io.opaa.api.types.ScheduleFrequency;
import io.opaa.common.ValidationException;
import io.opaa.indexing.job.LibraryScheduleCodec;

/** The rules every way of creating or changing a library applies to its own fields (German 400). */
final class LibraryFields {

  private static final int MAX_NAME_LENGTH = 255;
  private static final int MAX_DESCRIPTION_LENGTH = 2000;

  private LibraryFields() {}

  /** The trimmed name; refuses a missing or overlong one. */
  static String name(String name) {
    if (name == null || name.isBlank()) {
      throw new ValidationException("name ist erforderlich");
    }
    String trimmed = name.trim();
    if (trimmed.length() > MAX_NAME_LENGTH) {
      throw new ValidationException("name darf höchstens " + MAX_NAME_LENGTH + " Zeichen umfassen");
    }
    return trimmed;
  }

  static void description(String description) {
    if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
      throw new ValidationException(
          "description darf höchstens " + MAX_DESCRIPTION_LENGTH + " Zeichen umfassen");
    }
  }

  /**
   * {@code request} checked against the four intervals of {@link ScheduleFrequency}, as the {@code
   * (enabled, cron)} pair the library stores; anything but {@code DISABLED} needs a library with
   * runs ({@code indexingRun}), mirroring {@code chk_knowledge_libraries_schedule}.
   */
  static Schedule schedule(LibraryScheduleUpdate request, boolean indexingRun) {
    ScheduleFrequency frequency = request.frequency();
    if (frequency == null) {
      throw new ValidationException("frequency ist erforderlich");
    }
    if (frequency != ScheduleFrequency.DISABLED && !indexingRun) {
      throw new ValidationException(
          "Ein Zeitplan ist nur für Konnektorbibliotheken verfügbar, nicht für UPLOAD");
    }
    Integer hour = request.hour();
    Integer minute = request.minute();
    var weekday = request.weekday();
    switch (frequency) {
      case DISABLED, HOURLY -> {
        if (hour != null || minute != null || weekday != null) {
          throw new ValidationException(
              "hour, minute und weekday sind für frequency " + frequency + " nicht zulässig");
        }
      }
      case DAILY -> {
        if (hour == null || minute == null) {
          throw new ValidationException(
              "hour und minute sind erforderlich, wenn frequency DAILY ist");
        }
        if (weekday != null) {
          throw new ValidationException("weekday ist für frequency DAILY nicht zulässig");
        }
      }
      case WEEKLY -> {
        if (hour == null || minute == null || weekday == null) {
          throw new ValidationException(
              "hour, minute und weekday sind erforderlich, wenn frequency WEEKLY ist");
        }
      }
    }
    if (frequency == ScheduleFrequency.DISABLED) {
      return new Schedule(false, null);
    }
    return new Schedule(true, LibraryScheduleCodec.toCron(frequency, hour, minute, weekday));
  }

  /** The validated {@code (enabled, cron)} pair {@code KnowledgeLibrary#updateSchedule} takes. */
  record Schedule(boolean enabled, String cron) {}
}
