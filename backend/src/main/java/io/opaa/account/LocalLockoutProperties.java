package io.opaa.account;

import io.opaa.compliance.InstallationWide;
import io.opaa.compliance.NoDataImpact;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code opaa.auth.local.lockout.*} (ADR-0060, Entscheidungen 2 and 17): the limits of the
 * operation within which the system administration sets the lockout after failed sign-ins in {@link
 * LocalAuthSettings}, so the protection cannot be switched off from the application. A limit below
 * one attempt, a non-positive shortest lockout or one beyond the longest the administration can set
 * refuses the start, naming the variable.
 *
 * @param maxAttempts the most failed sign-ins the administration may allow before the lock (default
 *     10)
 * @param minDuration the shortest lockout the administration may set, counted in whole minutes
 *     rounded up (default 5 minutes, at most 24 hours)
 */
@InstallationWide
@NoDataImpact("a protection limit against guessing")
@ConfigurationProperties(prefix = "opaa.auth.local.lockout")
public record LocalLockoutProperties(
    Integer maxAttempts,
    @NoDataImpact("the shortest lockout the administration may set; it keeps no data")
        Duration minDuration) {

  public static final String MAX_ATTEMPTS_VARIABLE = "OPAA_AUTH_LOCAL_LOCKOUT_MAX_ATTEMPTS";
  public static final String MIN_DURATION_VARIABLE = "OPAA_AUTH_LOCAL_LOCKOUT_MIN_DURATION";

  private static final long NANOS_PER_MINUTE = Duration.ofMinutes(1).toNanos();

  public LocalLockoutProperties {
    maxAttempts = maxAttempts != null ? maxAttempts : 10;
    minDuration = minDuration != null ? minDuration : Duration.ofMinutes(5);
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
        || minDuration.compareTo(Duration.ofMinutes(LocalAuthSettings.MAX_LOCKOUT_DURATION_MINUTES))
            > 0) {
      throw new IllegalArgumentException(
          "opaa.auth.local.lockout.min-duration ("
              + MIN_DURATION_VARIABLE
              + ") must be positive and at most "
              + LocalAuthSettings.MAX_LOCKOUT_DURATION_MINUTES
              + " minutes, got "
              + minDuration);
    }
  }

  /** The shortest lockout in the whole minutes the administration sets, rounded up. */
  public int minDurationMinutes() {
    return (int) ((minDuration.toNanos() + NANOS_PER_MINUTE - 1) / NANOS_PER_MINUTE);
  }
}
