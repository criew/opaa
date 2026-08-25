package io.opaa.library;

import io.opaa.api.types.ScheduleFrequency;
import io.opaa.api.types.ScheduleWeekday;
import java.time.Instant;

/**
 * The resolved schedule half of a {@link LibraryManagementDetail} - mirrors the generated {@code
 * LibrarySchedule}.
 */
public record LibraryScheduleDetail(
    ScheduleFrequency frequency,
    Integer hour,
    Integer minute,
    ScheduleWeekday weekday,
    Instant nextRunAt) {}
