package io.opaa.connection.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The thresholds and periods of the connections: their factory defaults are the values an
 * installation had without configuration, so an update changes nothing on its own, and their bounds
 * live in code (ADR-0041, Entscheidung 7).
 */
class ConnectionLifecycleSettingTest {

  @Test
  void theFactoryDefaultsAreTheValuesDeliveredBefore() {
    ConnectionLifecycleSettings settings = TestLifecycleSettings.factoryDefaults();

    assertThat(settings.inactivityThreshold()).isEqualTo(Duration.ofDays(90));
    assertThat(settings.privateLibraryDeletionPeriod()).isEqualTo(Duration.ofDays(30));
    assertThat(settings.expiredConnectionWarningThreshold()).isEqualTo(10);
  }

  @Test
  void theBoundsAreFixedInCode() {
    assertThat(ConnectionLifecycleSetting.INACTIVITY_THRESHOLD_DAYS.min()).isEqualTo(30);
    assertThat(ConnectionLifecycleSetting.INACTIVITY_THRESHOLD_DAYS.max()).isEqualTo(365);
    assertThat(ConnectionLifecycleSetting.PRIVATE_LIBRARY_DELETION_DAYS.min()).isEqualTo(1);
    assertThat(ConnectionLifecycleSetting.PRIVATE_LIBRARY_DELETION_DAYS.max()).isEqualTo(90);
    assertThat(ConnectionLifecycleSetting.EXPIRED_CONNECTION_WARNING_THRESHOLD.min()).isEqualTo(1);
    assertThat(ConnectionLifecycleSetting.EXPIRED_CONNECTION_WARNING_THRESHOLD.max())
        .isEqualTo(1000);
    for (ConnectionLifecycleSetting setting : ConnectionLifecycleSetting.values()) {
      assertThat(setting.admits(setting.min())).isTrue();
      assertThat(setting.admits(setting.max())).isTrue();
      assertThat(setting.admits(setting.factoryDefault())).isTrue();
      assertThat(setting.admits(setting.min() - 1)).isFalse();
      assertThat(setting.admits(setting.max() + 1)).isFalse();
    }
  }

  @Test
  void aValueTheAdministrationSetIsReadOnEveryCall() {
    ConnectionLifecycleSettings settings =
        TestLifecycleSettings.with(ConnectionLifecycleSetting.PRIVATE_LIBRARY_DELETION_DAYS, 7);

    assertThat(settings.privateLibraryDeletionPeriod()).isEqualTo(Duration.ofDays(7));
    assertThat(settings.inactivityThreshold()).isEqualTo(Duration.ofDays(90));
  }

  @Test
  void aValueOutsideItsBoundsIsNeverStored() {
    ConnectionLifecycleSettingRepository repository =
        mock(ConnectionLifecycleSettingRepository.class);
    ConnectionLifecycleSettings settings = new ConnectionLifecycleSettings(repository);

    assertThatThrownBy(() -> settings.set(ConnectionLifecycleSetting.INACTIVITY_THRESHOLD_DAYS, 29))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> settings.set(ConnectionLifecycleSetting.PRIVATE_LIBRARY_DELETION_DAYS, 91))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> settings.set(ConnectionLifecycleSetting.EXPIRED_CONNECTION_WARNING_THRESHOLD, 0))
        .isInstanceOf(IllegalArgumentException.class);
    verify(repository, never()).upsert(anyString(), anyInt());
  }
}
