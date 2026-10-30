package io.opaa.account;

import jakarta.annotation.PostConstruct;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Names at start the limits of the lockout after failed sign-ins in force on this instance, a raise
 * by the retired {@code OPAA_AUTH_LOCAL_LOCKOUT_DURATION} included, and warns when that value is
 * capped at the longest lockout. Only logs; each instance reports its own environment (ADR-0053).
 */
@Component
class LocalLockoutStartupReport {

  private static final Logger log = LoggerFactory.getLogger(LocalLockoutStartupReport.class);

  private final LocalLockoutProperties limits;

  LocalLockoutStartupReport(LocalLockoutProperties limits) {
    this.limits = limits;
  }

  @PostConstruct
  void report() {
    log.info(info(limits));
    warning(limits).ifPresent(log::warn);
  }

  static String info(LocalLockoutProperties limits) {
    String line =
        "Lockout after failed sign-ins: the administration may allow at most "
            + limits.maxAttempts()
            + " attempts and must lock at least "
            + limits.minDurationMinutes()
            + " minutes";
    if (limits.duration() == null) {
      return line;
    }
    return line
        + " (raised by "
        + LocalLockoutProperties.RETIRED_DURATION_VARIABLE
        + "="
        + limits.duration()
        + ")";
  }

  static Optional<String> warning(LocalLockoutProperties limits) {
    if (!limits.legacyDurationCapped()) {
      return Optional.empty();
    }
    return Optional.of(
        LocalLockoutProperties.RETIRED_DURATION_VARIABLE
            + "="
            + limits.duration()
            + " exceeds the longest lockout of "
            + LocalAuthSettings.MAX_LOCKOUT_DURATION_MINUTES
            + " minutes; the shortest lockout is capped at "
            + LocalAuthSettings.MAX_LOCKOUT_DURATION_MINUTES
            + " minutes");
  }
}
