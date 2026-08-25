package io.opaa.api.types;

/**
 * The four fixed intervalstufen a library's indexing schedule may take (#485, Zuschnitt 21.08.2026)
 * - deliberately not free-form cron: HOURLY runs on the hour, DAILY and WEEKLY take an explicit
 * hour/minute (WEEKLY additionally a weekday), DISABLED carries no time at all. Stored internally
 * as a cron expression so a future expert cron field would only be a UI change, but the API itself
 * only ever accepts one of these four shapes.
 */
public enum ScheduleFrequency {
  DISABLED,
  HOURLY,
  DAILY,
  WEEKLY
}
