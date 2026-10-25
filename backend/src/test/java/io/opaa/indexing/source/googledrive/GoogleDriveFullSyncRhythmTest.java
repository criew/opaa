package io.opaa.indexing.source.googledrive;

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
import org.junit.jupiter.api.Test;

/** A Google Drive library without a rhythm of its own runs under the value set for Google Drive. */
class GoogleDriveFullSyncRhythmTest {

  private final UUID libraryId = UUID.randomUUID();
  private final FullSyncRhythmSettingRepository rhythms =
      mock(FullSyncRhythmSettingRepository.class);
  private final SourceSyncState state = mock(SourceSyncState.class);

  @Test
  void theValueOfTheAdministrationForGoogleDriveDecidesTheRunMode() {
    SourceSyncStateRepository states = mock(SourceSyncStateRepository.class);
    when(states.findByLibraryId(libraryId)).thenReturn(Optional.of(state));
    when(state.canReadChanges(anySet(), eq(Duration.ofDays(9)), any())).thenReturn(true);
    KnowledgeLibrary library = mock(KnowledgeLibrary.class);
    when(library.getId()).thenReturn(libraryId);
    GoogleDriveIndexingExecutor executor =
        new GoogleDriveIndexingExecutor(
            null,
            null,
            null,
            null,
            new ScanJournal(states),
            new FullSyncRhythm(rhythms),
            Clock.systemUTC(),
            null,
            null);
    ConnectorData settings = ConnectorData.of(Map.of("scopes", List.of(Map.of("drive", "drive0"))));

    assertThat(executor.defaultRunMode(library, settings)).isEqualTo(IndexingRunMode.FULL);
    when(rhythms.findDays("GOOGLE_DRIVE")).thenReturn(Optional.of(9));
    assertThat(executor.defaultRunMode(library, settings)).isEqualTo(IndexingRunMode.INCREMENTAL);
  }
}
