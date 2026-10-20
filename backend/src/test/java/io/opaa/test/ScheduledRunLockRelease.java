package io.opaa.test;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Frees every scheduler lock, standing in for the lapse of {@code lockAtLeastFor} between two ticks
 * a test drives itself: without it, a second call of a locked {@code @Scheduled} method within the
 * lock period is skipped, as it is in production. The rows are expired, not deleted: the lock
 * provider inserts a row only once per process and updates it after that.
 */
public final class ScheduledRunLockRelease {

  private ScheduledRunLockRelease() {}

  public static void releaseAll(JdbcTemplate jdbcTemplate) {
    jdbcTemplate.update("UPDATE shedlock SET lock_until = locked_at WHERE lock_until > locked_at");
  }
}
