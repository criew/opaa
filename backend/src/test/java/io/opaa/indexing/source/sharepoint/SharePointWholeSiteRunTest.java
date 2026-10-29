package io.opaa.indexing.source.sharepoint;

import static io.opaa.indexing.source.sharepoint.SharePointTestStores.DRIVE_0;
import static io.opaa.indexing.source.sharepoint.SharePointTestStores.DRIVE_1;
import static io.opaa.indexing.source.sharepoint.SharePointTestStores.SITE;
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
import io.opaa.msgraph.FakeGraphServer;
import io.opaa.test.ProductionDocumentFormats;
import java.nio.charset.StandardCharsets;
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
 * Runs of a library that takes a site whole: each run lists the site's document libraries anew, a
 * vanished one is reconciled away by the full sync, a new one makes the next run a full sync, and a
 * site without any leaves the index as it is.
 */
class SharePointWholeSiteRunTest {

  private final IndexingJobService jobService = mock(IndexingJobService.class);
  private final IndexingRunEventRepository eventRepository = mock(IndexingRunEventRepository.class);
  private final StaleDocumentCleanupService cleanupService =
      mock(StaleDocumentCleanupService.class);
  private final DocumentRepository documentRepository = mock(DocumentRepository.class);
  private final DocumentIngestService ingestService = mock(DocumentIngestService.class);
  private final SourceSyncStateRepository states = mock(SourceSyncStateRepository.class);
  private final List<String> notes = new ArrayList<>();
  private FakeGraphServer server;
  private SharePointIndexingExecutor executor;
  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() throws Exception {
    server = new FakeGraphServer();
    SharePointTestStores.twoLibraries(server);
    server.file(
        DRIVE_0,
        "a",
        "a.txt",
        FakeGraphServer.rootId(DRIVE_0),
        "a".getBytes(StandardCharsets.UTF_8));
    server.file(
        DRIVE_1,
        "b",
        "b.txt",
        FakeGraphServer.rootId(DRIVE_1),
        "b".getBytes(StandardCharsets.UTF_8));
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
        new SharePointIndexingExecutor(
            SharePointTestStores.connections(server),
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
    library = library(SITE);
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private KnowledgeLibrary library(String site) {
    KnowledgeLibrary created =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Team",
            null,
            UUID.randomUUID(),
            SharePointSourceConnector.TYPE,
            null,
            server.origin().toString(),
            FakeGraphServer.TOKEN);
    created.updateSourceSettings(
        ConnectorData.of(
                Map.of("libraries", List.of(Map.of("site", Map.of("id", site, "name", "Team")))))
            .toJson());
    return created;
  }

  @SuppressWarnings("unchecked")
  private Set<String> reconciledPaths() {
    ArgumentCaptor<Set<String>> present = ArgumentCaptor.forClass(Set.class);
    verify(cleanupService)
        .reconcile(
            eq(library),
            eq(SharePointSourceConnector.TYPE),
            present.capture(),
            any(),
            any(),
            eq(executor),
            eq(IndexingRunMode.FULL));
    return present.getValue();
  }

  @Test
  void aLibraryGoneFromTheSiteIsNamedAndReconciledAwayByTheFullSync() throws Exception {
    SourceSyncState state = new SourceSyncState(library.getId());
    state.rememberResolvedScopes(
        Map.of(
            "drive:" + DRIVE_0,
            "Team / Dokumente",
            "drive:" + DRIVE_1,
            "Team / Akten",
            "drive:b!weg",
            "Team / Archiv"));
    when(states.findByLibraryId(library.getId())).thenReturn(Optional.of(state));

    executor.execute(UUID.randomUUID(), library, IndexingRunMode.FULL);

    assertThat(notes)
        .contains("Umfang „Alle“: 2 Dokumentbibliotheken")
        .anySatisfy(
            note ->
                assertThat(note)
                    .startsWith("Nicht mehr im Umfang: 1 Dokumentbibliothek – „Team / Archiv“")
                    .endsWith("entfernt dieser Vollabgleich"));
    verify(ingestService, times(2)).ingest(any(), any());
    assertThat(reconciledPaths()).hasSize(2);
  }

  @Test
  void aLibraryNewOnTheSiteMakesTheNextRunAFullSync() {
    SourceSyncState state = new SourceSyncState(library.getId());
    state.completeFullSync(Instant.now());
    state.advanceChangeCursor("drive:" + DRIVE_0, "cursor-0");
    state.advanceChangeCursor("drive:" + DRIVE_1, "cursor-1");
    state.rememberResolvedScopes(
        Map.of("drive:" + DRIVE_0, "Team / Dokumente", "drive:" + DRIVE_1, "Team / Akten"));
    when(states.findByLibraryId(library.getId())).thenReturn(Optional.of(state));
    ConnectorData settings = ConnectorData.storedIn(library);

    assertThat(executor.defaultRunMode(library, settings)).isEqualTo(IndexingRunMode.INCREMENTAL);

    state.rememberResolvedScopes(
        Map.of(
            "drive:" + DRIVE_0,
            "Team / Dokumente",
            "drive:" + DRIVE_1,
            "Team / Akten",
            "drive:b!neu",
            "Team / Neu"));

    assertThat(executor.defaultRunMode(library, settings))
        .as("the new library has no change cursor yet")
        .isEqualTo(IndexingRunMode.FULL);
  }

  @Test
  void aSiteWithoutAnyDocumentLibraryFailsTheRunAndLeavesTheIndexAlone() throws Exception {
    String empty =
        "contoso.sharepoint.com,3c712604-1370-44e7-a1f5-426573fda80a,"
            + "3d2244c3-251a-49ea-93a8-39e1c3a060fe";
    server.site(empty, "contoso.sharepoint.com", "/sites/leer", "Leer");
    library = library(empty);
    UUID jobId = UUID.randomUUID();

    executor.execute(jobId, library, IndexingRunMode.FULL);

    verify(jobService).failJob(eq(jobId), eq(SharePointIndexingExecutor.NO_VISIBLE_LIBRARY));
    verify(cleanupService, never()).reconcile(any(), any(), any(), any(), any(), any(), any());
    verify(ingestService, never()).ingest(any(), any());
  }
}
