package io.opaa.indexing.source.googledrive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.credentials.LibrarySourceConnectionResolver;
import io.opaa.indexing.document.DocumentIngestResult;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.job.IndexingJobService;
import io.opaa.indexing.job.IndexingRunEvent;
import io.opaa.indexing.job.IndexingRunEventRepository;
import io.opaa.indexing.maintenance.StaleDocumentCleanupService;
import io.opaa.indexing.run.IndexingRunTemplate;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.FullSyncRhythm;
import io.opaa.indexing.spi.FullSyncRhythmSettingRepository;
import io.opaa.indexing.spi.ScanJournal;
import io.opaa.indexing.spi.SourceSyncState;
import io.opaa.indexing.spi.SourceSyncStateRepository;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.knowledge.LibraryStorageQuotaService;
import io.opaa.security.TargetAddressValidator;
import io.opaa.test.ProductionDocumentFormats;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Runs under "Alle Ablagen": each run lists the shared drives anew, a vanished one is reconciled
 * away by the full sync, a new one makes the next run a full sync, and a resolution without any
 * drive leaves the index as it is.
 */
class GoogleDriveAllScopesRunTest {

  private final IndexingJobService jobService = mock(IndexingJobService.class);
  private final IndexingRunEventRepository eventRepository = mock(IndexingRunEventRepository.class);
  private final StaleDocumentCleanupService cleanupService =
      mock(StaleDocumentCleanupService.class);
  private final DocumentRepository documentRepository = mock(DocumentRepository.class);
  private final DocumentIngestService ingestService = mock(DocumentIngestService.class);
  private final SourceSyncStateRepository states = mock(SourceSyncStateRepository.class);
  private final List<String> notes = new ArrayList<>();
  private FakeDriveServer server;
  private GoogleDriveIndexingExecutor executor;
  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() throws Exception {
    server = new FakeDriveServer();
    when(ingestService.ingest(any(), any())).thenReturn(DocumentIngestResult.PROCESSED);
    when(states.findByLibraryId(any())).thenReturn(Optional.empty());
    when(states.save(any())).thenAnswer(call -> call.getArgument(0));
    when(eventRepository.save(any()))
        .thenAnswer(
            call -> {
              notes.add(call.<IndexingRunEvent>getArgument(0).getMessage());
              return call.getArgument(0);
            });
    executor =
        new GoogleDriveIndexingExecutor(
            new DriveApiFactory(
                GoogleDriveProperties.defaults(), TargetAddressValidator.disabled(), wait -> {}),
            ingestService,
            documentRepository,
            mock(LibraryFolderService.class),
            new ScanJournal(states),
            new FullSyncRhythm(mock(FullSyncRhythmSettingRepository.class)),
            Clock.systemUTC(),
            new IndexingRunTemplate(
                jobService,
                eventRepository,
                cleanupService,
                documentRepository,
                mock(LibraryStorageQuotaService.class),
                new LibrarySourceConnectionResolver(),
                Clock.systemUTC()),
            ProductionDocumentFormats.supportedFormats());
    library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Ablagen",
            null,
            UUID.randomUUID(),
            GoogleDriveSourceConnector.TYPE,
            null,
            server.base().toString(),
            FakeDriveServer.TOKEN);
    library.updateSourceSettings(ConnectorData.of(Map.of("allScopes", true)).toJson());
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private void twoDrivesWithAFileEach() {
    server.addDrive("drive0", "Ablage");
    server.addDrive("drive1", "Archiv");
    server.file("a", "a.txt", "text/plain", "drive0", "drive0", "a");
    server.file("b", "b.txt", "text/plain", "drive1", "drive1", "b");
  }

  @SuppressWarnings("unchecked")
  private Set<String> reconciledPaths() {
    ArgumentCaptor<Set<String>> present = ArgumentCaptor.forClass(Set.class);
    verify(cleanupService)
        .reconcile(
            eq(library),
            eq(GoogleDriveSourceConnector.TYPE),
            present.capture(),
            any(),
            any(),
            eq(executor),
            eq(IndexingRunMode.FULL));
    return present.getValue();
  }

  @Test
  void aDriveTheAccountNoLongerSeesIsNamedAndReconciledAwayByTheFullSync() throws Exception {
    twoDrivesWithAFileEach();
    SourceSyncState state = new SourceSyncState(library.getId());
    state.rememberResolvedScopes(
        Map.of("drive:drive0", "Ablage", "drive:drive1", "Archiv", "drive:weg", "Alt"));
    when(states.findByLibraryId(library.getId())).thenReturn(Optional.of(state));

    executor.execute(UUID.randomUUID(), library, IndexingRunMode.FULL);

    assertThat(notes)
        .contains("Umfang „Alle“: 2 Ablagen")
        .anySatisfy(
            note ->
                assertThat(note)
                    .startsWith("Nicht mehr im Umfang: 1 Ablage – „Alt“")
                    .endsWith("entfernt dieser Vollabgleich"));
    verify(ingestService, times(2)).ingest(any(), any());
    assertThat(reconciledPaths()).hasSize(2);
  }

  @Test
  void aDriveNewToTheAccountMakesTheNextRunAFullSync() {
    SourceSyncState state = new SourceSyncState(library.getId());
    state.completeFullSync(Instant.now());
    state.advanceChangeCursor("drive:drive0", "cursor-0");
    state.advanceChangeCursor("drive:drive1", "cursor-1");
    state.rememberResolvedScopes(Map.of("drive:drive0", "Ablage", "drive:drive1", "Archiv"));
    when(states.findByLibraryId(library.getId())).thenReturn(Optional.of(state));
    ConnectorData settings = ConnectorData.storedIn(library);

    assertThat(executor.defaultRunMode(library, settings)).isEqualTo(IndexingRunMode.INCREMENTAL);

    state.rememberResolvedScopes(
        Map.of("drive:drive0", "Ablage", "drive:drive1", "Archiv", "drive:drive2", "Projekte"));

    assertThat(executor.defaultRunMode(library, settings))
        .as("the new drive has no change cursor yet")
        .isEqualTo(IndexingRunMode.FULL);
  }

  @Test
  void aResolutionWithoutAnyDriveFailsTheRunAndLeavesTheIndexAlone() throws Exception {
    UUID jobId = UUID.randomUUID();

    executor.execute(jobId, library, IndexingRunMode.FULL);

    verify(jobService).failJob(eq(jobId), eq(GoogleDriveSourceConnector.NO_VISIBLE_SCOPE));
    verify(cleanupService, never()).reconcile(any(), any(), any(), any(), any(), any(), any());
    verify(ingestService, never()).ingest(any(), any());
  }
}
