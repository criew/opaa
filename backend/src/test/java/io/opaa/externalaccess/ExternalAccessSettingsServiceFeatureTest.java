package io.opaa.externalaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.UserRepository;
import io.opaa.common.Feature;
import io.opaa.common.FeatureFlags;
import io.opaa.common.TestFeatureFlags;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The installation switch of the channel sits below the installation-wide feature flag (ADR-0044):
 * the channel is open only while both are on. The stored switch itself stays untouched, so the
 * channel is open again as soon as the feature is switched back on.
 */
class ExternalAccessSettingsServiceFeatureTest {

  private final ExternalAccessSettingsRepository repository =
      mock(ExternalAccessSettingsRepository.class);

  private ExternalAccessSettingsService serviceWith(FeatureFlags flags) {
    return new ExternalAccessSettingsService(
        repository,
        mock(UserRepository.class),
        mock(AuditEventRecorder.class),
        Clock.systemUTC(),
        flags);
  }

  private void storedSwitch(boolean enabled) {
    ExternalAccessSettings row = mock(ExternalAccessSettings.class);
    when(row.isEnabled()).thenReturn(enabled);
    when(repository.findSingleton()).thenReturn(Optional.of(row));
  }

  @Test
  void withTheFeatureOnTheStoredSwitchDecides() {
    ExternalAccessSettingsService service = serviceWith(TestFeatureFlags.allOn());

    storedSwitch(true);
    assertThat(service.isEnabled()).isTrue();
    storedSwitch(false);
    assertThat(service.isEnabled()).isFalse();
  }

  @Test
  void withTheFeatureOffTheChannelIsClosedWhateverTheStoredSwitchSays() {
    storedSwitch(true);

    assertThat(serviceWith(TestFeatureFlags.without(Feature.EXTERNAL_ACCESS)).isEnabled())
        .isFalse();
  }
}
