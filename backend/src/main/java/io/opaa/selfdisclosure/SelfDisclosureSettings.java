package io.opaa.selfdisclosure;

import java.time.Instant;

/**
 * The installation-wide switch and daily limit of the self-disclosure, read from the database on
 * every request, so a change acts on all instances at once without a cache (ADR-0053).
 *
 * @param enabled whether persons may export at all (emergency switch, on by default)
 * @param dailyLimit completed exports per person and calendar day, 1 to 10
 * @param updatedAt last change, by the database clock
 */
public record SelfDisclosureSettings(boolean enabled, int dailyLimit, Instant updatedAt) {

  public static final int MIN_DAILY_LIMIT = 1;
  public static final int MAX_DAILY_LIMIT = 10;

  /** Starts per person and day on top of the daily limit, for aborted attempts. */
  public static final int EXTRA_STARTS = 4;

  public int maxStarts() {
    return dailyLimit + EXTRA_STARTS;
  }
}
