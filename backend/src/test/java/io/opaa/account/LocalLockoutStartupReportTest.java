package io.opaa.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * {@link LocalLockoutStartupReport}: the start names the limits of the lockout in force, a raise by
 * the retired {@code OPAA_AUTH_LOCAL_LOCKOUT_DURATION} included, and warns when that value exceeds
 * the longest lockout and is capped.
 */
class LocalLockoutStartupReportTest {

  @Test
  void namesTheLimitsInForceWithoutARetiredValue() {
    LocalLockoutProperties limits = new LocalLockoutProperties(null, null, null);

    assertThat(LocalLockoutStartupReport.info(limits))
        .isEqualTo(
            "Lockout after failed sign-ins: the administration may allow at most 5 attempts and"
                + " must lock at least 15 minutes");
    assertThat(LocalLockoutStartupReport.warning(limits)).isEmpty();
  }

  @Test
  void namesTheRaiseByTheRetiredDuration() {
    LocalLockoutProperties limits =
        new LocalLockoutProperties(null, null, Duration.ofMinutes(60));

    assertThat(LocalLockoutStartupReport.info(limits))
        .isEqualTo(
            "Lockout after failed sign-ins: the administration may allow at most 5 attempts and"
                + " must lock at least 60 minutes (raised by OPAA_AUTH_LOCAL_LOCKOUT_DURATION=PT1H)");
    assertThat(LocalLockoutStartupReport.warning(limits)).isEmpty();
  }

  @Test
  void warnsWhenTheRetiredDurationIsCapped() {
    LocalLockoutProperties limits = new LocalLockoutProperties(null, null, Duration.ofDays(7));

    assertThat(LocalLockoutStartupReport.info(limits)).contains("at least 1440 minutes");
    assertThat(LocalLockoutStartupReport.warning(limits))
        .contains(
            "OPAA_AUTH_LOCAL_LOCKOUT_DURATION=PT168H exceeds the longest lockout of 1440 minutes;"
                + " the shortest lockout is capped at 1440 minutes");
  }
}
