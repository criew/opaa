package io.opaa.indexing.source.dropbox;

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

/** A Dropbox library without a rhythm of its own runs under the value set for Dropbox. */
class DropboxFullSyncRhythmTest {

  private final UUID libraryId = UUID.randomUUID();
  private final FullSyncRhythmSettingRepository rhythms =
      mock(FullSyncRhythmSettingRepository.class);
  private final SourceSyncState state = mock(SourceSyncState.class);

  @Test
  void theValueOfTheAdministrationForDropboxDecidesTheRunMode() {
    SourceSyncStateRepository states = mock(SourceSyncStateRepository.class);
    when(states.findByLibraryId(libraryId)).thenReturn(Optional.of(state));
    when(state.canReadChanges(anySet(), eq(Duration.ofDays(9)), any())).thenReturn(true);
    KnowledgeLibrary library = mock(KnowledgeLibrary.class);
    when(library.getId()).thenReturn(libraryId);
    DropboxIndexingExecutor executor =
        new DropboxIndexingExecutor(
            null,
            null,
            null,
            null,
            new ScanJournal(states),
            new FullSyncRhythm(rhythms),
            Clock.systemUTC(),
            null,
            null);
    ConnectorData settings = ConnectorData.of(Map.of("folders", List.of("/Akten")));

    assertThat(executor.defaultRunMode(library, settings)).isEqualTo(IndexingRunMode.FULL);
    when(rhythms.findDays("DROPBOX")).thenReturn(Optional.of(9));
    assertThat(executor.defaultRunMode(library, settings)).isEqualTo(IndexingRunMode.INCREMENTAL);
  }
}
