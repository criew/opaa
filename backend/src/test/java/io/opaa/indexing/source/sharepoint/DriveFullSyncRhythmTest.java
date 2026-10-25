package io.opaa.indexing.source.sharepoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.FullSyncRhythm;
import io.opaa.indexing.spi.FullSyncRhythmSettingRepository;
import io.opaa.indexing.spi.ScanJournal;
import io.opaa.indexing.spi.SourceSyncState;
import io.opaa.indexing.spi.SourceSyncStateRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SharePoint and OneDrive share their code but not their rhythm: each library without a rhythm of
 * its own runs under the value set for its own source type.
 */
class DriveFullSyncRhythmTest {

  private final UUID libraryId = UUID.randomUUID();
  private final FullSyncRhythmSettingRepository rhythms =
      mock(FullSyncRhythmSettingRepository.class);
  private final SourceSyncState state = mock(SourceSyncState.class);
  private final KnowledgeLibrary library = mock(KnowledgeLibrary.class);
  private ScanJournal journal;

  @BeforeEach
  void arrange() {
    SourceSyncStateRepository states = mock(SourceSyncStateRepository.class);
    when(states.findByLibraryId(libraryId)).thenReturn(Optional.of(state));
    when(state.canReadChanges(anySet(), eq(Duration.ofDays(9)), any())).thenReturn(true);
    when(library.getId()).thenReturn(libraryId);
    journal = new ScanJournal(states);
  }

  @Test
  void theValueOfTheAdministrationForSharePointDecidesTheRunMode() {
    SharePointIndexingExecutor executor =
        new SharePointIndexingExecutor(
            mock(GraphConnections.class),
            null,
            null,
            null,
            journal,
            new FullSyncRhythm(rhythms),
            Clock.systemUTC(),
            null,
            null);
    ConnectorData settings =
        ConnectorData.of(Map.of("libraries", List.of(Map.of("driveId", "b!drive-0"))));

    when(rhythms.findDays("ONEDRIVE")).thenReturn(Optional.of(9));
    assertThat(executor.defaultRunMode(library, settings)).isEqualTo(IndexingRunMode.FULL);
    when(rhythms.findDays("SHAREPOINT")).thenReturn(Optional.of(9));
    assertThat(executor.defaultRunMode(library, settings)).isEqualTo(IndexingRunMode.INCREMENTAL);
  }

  @Test
  void theValueOfTheAdministrationForOneDriveDecidesTheRunMode() {
    OneDriveIndexingExecutor executor =
        new OneDriveIndexingExecutor(
            mock(GraphConnections.class),
            null,
            null,
            null,
            journal,
            new FullSyncRhythm(rhythms),
            Clock.systemUTC(),
            null,
            null);
    ConnectorData settings = OneDriveTestDrives.drive(OneDriveTestDrives.DRIVE_ANNA);

    when(rhythms.findDays("SHAREPOINT")).thenReturn(Optional.of(9));
    assertThat(executor.defaultRunMode(library, settings)).isEqualTo(IndexingRunMode.FULL);
    when(rhythms.findDays("ONEDRIVE")).thenReturn(Optional.of(9));
    assertThat(executor.defaultRunMode(library, settings)).isEqualTo(IndexingRunMode.INCREMENTAL);
  }
}
