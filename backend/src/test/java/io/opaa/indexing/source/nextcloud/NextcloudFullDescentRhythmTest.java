package io.opaa.indexing.source.nextcloud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.indexing.spi.FullSyncRhythm;
import io.opaa.indexing.spi.FullSyncRhythmSettingRepository;
import io.opaa.indexing.spi.ScanJournal;
import io.opaa.indexing.spi.SourceSyncStateRepository;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.SourceRequestPolicy;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The full descent of Nextcloud follows the full-sync rhythm of the installation: seven days from
 * the factory, the days the administration set for Nextcloud once it set some.
 */
class NextcloudFullDescentRhythmTest {

  private final FullSyncRhythmSettingRepository rhythms =
      mock(FullSyncRhythmSettingRepository.class);

  @Test
  void theDescriptorNamesTheFactoryDefaultOfSevenDays() {
    NextcloudSourceConnector connector =
        new NextcloudSourceConnector(
            NextcloudProperties.defaults(),
            TargetAddressValidator.disabled(),
            SourceRequestPolicy.defaults(),
            mock(SourceSyncStateRepository.class));

    assertThat(connector.descriptor().fullSyncInterval()).isEqualTo(Duration.ofDays(7));
    assertThat(NextcloudSourceConnector.FULL_DESCENT_INTERVAL).isEqualTo(Duration.ofDays(7));
  }

  @Test
  void theValueOfTheAdministrationForNextcloudBoundsTheRememberedFolderMarkers() {
    NextcloudIndexingExecutor executor =
        new NextcloudIndexingExecutor(
            NextcloudProperties.defaults(),
            TargetAddressValidator.disabled(),
            SourceRequestPolicy.defaults(),
            null,
            null,
            null,
            new ScanJournal(mock(SourceSyncStateRepository.class)),
            new FullSyncRhythm(rhythms),
            Clock.systemUTC(),
            null,
            null);
    when(rhythms.findDays("NEXTCLOUD")).thenReturn(Optional.empty());

    assertThat(executor.fullDescentInterval()).isEqualTo(Duration.ofDays(7));

    when(rhythms.findDays("NEXTCLOUD")).thenReturn(Optional.of(3));
    assertThat(executor.fullDescentInterval()).isEqualTo(Duration.ofDays(3));
  }
}
