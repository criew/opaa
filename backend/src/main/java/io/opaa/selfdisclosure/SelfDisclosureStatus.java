package io.opaa.selfdisclosure;

import java.time.Instant;

/**
 * What the person learns about her own self-disclosure: whether it is available, what is left of
 * today, when the next export is possible and how the last attempt ended.
 *
 * @param blocked why no export can start now, {@code null} when one can
 * @param nextPossibleAt from when an export is possible again; {@code null} when now or never
 * @param lastResult {@code COMPLETED} or {@code ABORTED}, {@code null} before the first attempt
 * @param retryPossible whether the last attempt aborted and a new one can start now
 */
public record SelfDisclosureStatus(
    boolean enabled,
    int dailyLimit,
    int completedToday,
    int startsToday,
    int remainingExports,
    int remainingStarts,
    boolean running,
    Blocked blocked,
    Instant nextPossibleAt,
    Instant lastCompletedAt,
    String lastResult,
    boolean retryPossible) {

  /** Why an export cannot start now. */
  public enum Blocked {
    DISABLED,
    RUNNING_EXPORT,
    DAILY_LIMIT,
    STARTS_EXHAUSTED
  }
}
