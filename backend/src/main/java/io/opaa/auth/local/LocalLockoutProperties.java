package io.opaa.auth.local;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code opaa.auth.local.lockout.*} (ADR-0033, Entscheidung 9): after {@code maxAttempts} failed
 * sign-ins an account is locked for the fixed {@code duration} - no progressive extension; the
 * counter returns to zero on the next successful sign-in, reset or unlock. Defaults are the ADR's;
 * a threshold below one or a non-positive duration refuses the start, naming the variable.
 *
 * @param maxAttempts failed attempts that lock the account (default 5)
 * @param duration how long the lock lasts (default 15 minutes)
 */
@ConfigurationProperties(prefix = "opaa.auth.local.lockout")
public record LocalLockoutProperties(Integer maxAttempts, Duration duration) {

  public static final String MAX_ATTEMPTS_VARIABLE = "OPAA_AUTH_LOCAL_LOCKOUT_MAX_ATTEMPTS";
  public static final String DURATION_VARIABLE = "OPAA_AUTH_LOCAL_LOCKOUT_DURATION";

  public LocalLockoutProperties {
    maxAttempts = maxAttempts != null ? maxAttempts : 5;
    duration = duration != null ? duration : Duration.ofMinutes(15);
    if (maxAttempts < 1) {
      throw new IllegalArgumentException(
          "opaa.auth.local.lockout.max-attempts ("
              + MAX_ATTEMPTS_VARIABLE
              + ") must be at least 1, got "
              + maxAttempts);
    }
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException(
          "opaa.auth.local.lockout.duration ("
              + DURATION_VARIABLE
              + ") must be positive, got "
              + duration);
    }
  }
}
