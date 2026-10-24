package io.opaa.ratelimit;

import io.opaa.ratelimit.RateLimitService.Decision;
import java.time.Duration;

/**
 * Counters every instance of an installation shares (ADR-0053, Entscheidung 12): one per key and
 * window length, counted by the clock of the database. A counter estimates its sliding window from
 * the current and the previous fixed window, weighting the previous one by the share of it that
 * still lies inside the sliding window - the same estimate on every instance, whichever counted.
 *
 * <p>A key names a limit or a channel, never a client, a token or a person: a persisted count per
 * person would be a usage datum about her.
 */
public interface InstallationCounters {

  /**
   * Counts one event under {@code key} if fewer than {@code max} lie in the sliding window of
   * {@code window}; a refusal is not counted and names the seconds until the estimate has room.
   */
  Decision tryCount(String key, Duration window, int max);

  /** Gives back the event {@code granted} counted, while its fixed window is still the current. */
  void release(String key, Duration window, Decision granted);

  /** Counts one event under {@code key} without a bound and returns the estimate including it. */
  double count(String key, Duration window);

  /**
   * Claims the quiet period after an alert raised from {@code key}: {@code true} for exactly one
   * caller of the installation until {@code quietFor} has passed.
   */
  boolean claimQuietPeriod(String key, Duration window, Duration quietFor);

  /** Forgets the counter and its quiet period - for a test, and for nothing else. */
  void reset(String key, Duration window);
}
