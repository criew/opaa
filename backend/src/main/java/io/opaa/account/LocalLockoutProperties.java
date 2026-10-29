package io.opaa.account;

import io.opaa.compliance.InstallationWide;
import io.opaa.compliance.NoDataImpact;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code opaa.auth.local.lockout.*} (ADR-0060, Entscheidungen 2 and 17): the limits of the
 * operation within which the system administration sets the lockout after failed sign-ins in {@link
 * LocalAuthSettings}; by default the former fixed rule, so the administration can only tighten it.
 * A still set retired {@code duration} raises the shortest lockout, capped at the longest one. A
 * limit that would switch the protection off refuses the start, naming the variable.
 *
 * @param maxAttempts the most failed sign-ins the administration may allow before the lock (default
 *     5)
 * @param minDuration the shortest lockout the administration may set, counted in whole minutes
 *     rounded up (default 15 minutes, at most 24 hours)
 * @param duration retired: the former fixed lockout duration; while still set it raises the
 *     shortest lockout to its value, at most 24 hours
 */
@InstallationWide
@NoDataImpact("a protection limit against guessing")
@ConfigurationProperties(prefix = "opaa.auth.local.lockout")
public record LocalLockoutProperties(
    Integer maxAttempts,
    @NoDataImpact("the shortest lockout the administration may set; it keeps no data")
        Duration minDuration,
    @NoDataImpact("a retired lockout duration that only raises the shortest lockout")
        Duration duration) {

  public static final String MAX_ATTEMPTS_VARIABLE = "OPAA_AUTH_LOCAL_LOCKOUT_MAX_ATTEMPTS";
  public static final String MIN_DURATION_VARIABLE = "OPAA_AUTH_LOCAL_LOCKOUT_MIN_DURATION";
  public static final String RETIRED_DURATION_VARIABLE = "OPAA_AUTH_LOCAL_LOCKOUT_DURATION";

  private static final Duration LONGEST_LOCKOUT =
      Duration.ofMinutes(LocalAuthSettings.MAX_LOCKOUT_DURATION_MINUTES);
  private static final long NANOS_PER_MINUTE = Duration.ofMinutes(1).toNanos();

  public LocalLockoutProperties {
    maxAttempts =
        maxAttempts != null ? maxAttempts : LocalAuthSettings.DEFAULT_LOCKOUT_MAX_ATTEMPTS;
    minDuration =
        minDuration != null
            ? minDuration
            : Duration.ofMinutes(LocalAuthSettings.DEFAULT_LOCKOUT_DURATION_MINUTES);
    if (maxAttempts < LocalAuthSettings.MIN_LOCKOUT_MAX_ATTEMPTS) {
      throw new IllegalArgumentException(
          "opaa.auth.local.lockout.max-attempts ("
              + MAX_ATTEMPTS_VARIABLE
              + ") must be at least "
              + LocalAuthSettings.MIN_LOCKOUT_MAX_ATTEMPTS
              + ", got "
              + maxAttempts);
    }
    if (minDuration.isZero()
        || minDuration.isNegative()
        || minDuration.compareTo(LONGEST_LOCKOUT) > 0) {
      throw new IllegalArgumentException(
          "opaa.auth.local.lockout.min-duration ("
              + MIN_DURATION_VARIABLE
              + ") must be positive and at most "
              + LocalAuthSettings.MAX_LOCKOUT_DURATION_MINUTES
              + " minutes, got "
              + minDuration);
    }
    if (duration != null && (duration.isZero() || duration.isNegative())) {
      throw new IllegalArgumentException(
          "opaa.auth.local.lockout.duration ("
              + RETIRED_DURATION_VARIABLE
              + ") must be positive or removed, got "
              + duration);
    }
  }

  /**
   * The shortest lockout in the whole minutes the administration sets, rounded up: the configured
   * one, raised by a still set retired duration capped at the longest lockout.
   */
  public int minDurationMinutes() {
    Duration shortest = minDuration;
    if (duration != null) {
      Duration retired = duration.compareTo(LONGEST_LOCKOUT) > 0 ? LONGEST_LOCKOUT : duration;
      shortest = retired.compareTo(shortest) > 0 ? retired : shortest;
    }
    return (int) ((shortest.toNanos() + NANOS_PER_MINUTE - 1) / NANOS_PER_MINUTE);
  }

  /**
   * Whether a still set retired duration exceeds the longest lockout and only raises it to that.
   */
  public boolean legacyDurationCapped() {
    return duration != null && duration.compareTo(LONGEST_LOCKOUT) > 0;
  }
}
