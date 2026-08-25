package io.opaa.library;

import io.opaa.api.types.ScheduleFrequency;
import io.opaa.api.types.ScheduleWeekday;

/**
 * The requested schedule half of a {@link LibraryUpdate} - mirrors the generated {@code
 * LibraryScheduleRequest}, including its fluent {@code withX}-style setters for a low-friction test
 * call site.
 */
public record LibraryScheduleUpdate(
    ScheduleFrequency frequency, Integer hour, Integer minute, ScheduleWeekday weekday) {

  public LibraryScheduleUpdate(ScheduleFrequency frequency) {
    this(frequency, null, null, null);
  }

  public LibraryScheduleUpdate hour(Integer hour) {
    return new LibraryScheduleUpdate(frequency, hour, minute, weekday);
  }

  public LibraryScheduleUpdate minute(Integer minute) {
    return new LibraryScheduleUpdate(frequency, hour, minute, weekday);
  }

  public LibraryScheduleUpdate weekday(ScheduleWeekday weekday) {
    return new LibraryScheduleUpdate(frequency, hour, minute, weekday);
  }
}
