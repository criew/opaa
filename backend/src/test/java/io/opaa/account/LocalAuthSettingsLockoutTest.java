package io.opaa.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The lockout rule in force (ADR-0060, Entscheidung 17): the stored value, else the factory
 * default, always within the limits of the operation - a narrowed limit bounds a value stored
 * before, so the protection holds whatever the table says.
 */
class LocalAuthSettingsLockoutTest {

  private static final int LIMIT = 10;
  private static final int MIN_MINUTES = 5;

  @Test
  void withoutOwnValuesTheFactoryDefaultsApply() {
    LocalAuthSettings.Values values = LocalAuthSettings.Values.defaults();

    assertThat(values.lockoutMaxAttempts()).isNull();
    assertThat(values.lockoutDurationMinutes()).isNull();
    assertThat(values.lockoutMaxAttemptsInForce(LIMIT))
        .isEqualTo(LocalAuthSettings.DEFAULT_LOCKOUT_MAX_ATTEMPTS)
        .isEqualTo(5);
    assertThat(values.lockoutDurationMinutesInForce(MIN_MINUTES))
        .isEqualTo(LocalAuthSettings.DEFAULT_LOCKOUT_DURATION_MINUTES)
        .isEqualTo(15);
  }

  @Test
  void aStoredValueWithinTheLimitsApplies() {
    LocalAuthSettings.Values values = lockout(3, 60);

    assertThat(values.lockoutMaxAttemptsInForce(LIMIT)).isEqualTo(3);
    assertThat(values.lockoutDurationMinutesInForce(MIN_MINUTES)).isEqualTo(60);
  }

  @Test
  void aNarrowedLimitBoundsTheStoredValueAndTheFactoryDefault() {
    assertThat(lockout(8, 10).lockoutMaxAttemptsInForce(4)).isEqualTo(4);
    assertThat(lockout(8, 10).lockoutDurationMinutesInForce(30)).isEqualTo(30);
    assertThat(LocalAuthSettings.Values.defaults().lockoutMaxAttemptsInForce(3)).isEqualTo(3);
    assertThat(LocalAuthSettings.Values.defaults().lockoutDurationMinutesInForce(60))
        .isEqualTo(60);
  }

  @Test
  void theValuesThemselvesRefuseAnythingThatSwitchesTheProtectionOff() {
    assertThatThrownBy(() -> lockout(0, null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> lockout(null, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> lockout(null, LocalAuthSettings.MAX_LOCKOUT_DURATION_MINUTES + 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static LocalAuthSettings.Values lockout(Integer attempts, Integer minutes) {
    return new LocalAuthSettings.Values(
        false, List.of(), true, 12, 72, 30, 90, 90, attempts, minutes);
  }
}
