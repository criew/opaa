package io.opaa.integration.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ConfluenceEdition;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.IndexingEventCategory;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.IndexingRunEvent;
import io.opaa.indexing.IndexingRunEventRepository;
import io.opaa.indexing.SourceDocumentContext;
import io.opaa.indexing.StaleDocumentCleanupService;
import io.opaa.indexing.VectorChunkStore;
import io.opaa.indexing.source.attachment.AttachmentIndexer;
import io.opaa.indexing.source.confluence.ConfluenceClientFactory;
import io.opaa.indexing.source.confluence.ConfluenceIndexingExecutor;
import io.opaa.indexing.source.confluence.ConfluenceProperties;
import io.opaa.indexing.source.confluence.ConfluenceSyncStateRepository;
import io.opaa.library.ConfluenceSpaceSelection;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;

/**
 * The full sync (#1136) against the real Data Center: the executor with the real access layer,
 * everything behind it mocked - the vector pipeline is covered by the regular tests and needs an
 * embedding model this suite does not have. Two scenarios the epic names: a full run with a trashed
 * page and a restricted page, and two libraries against the same instance with different tokens and
 * selections that must not influence each other (ADR-0023, Entscheidung 5).
 */
@EnabledIfEnvironmentVariable(named = "OPAA_CONFLUENCE_IT", matches = "true")
class ConfluenceDataCenterFullSyncTest {

  private static ConfluenceDataCenterFixture confluence;
  private static ConfluenceClientFactory factory;
  private static ConfluenceProperties properties;

  private FileProcessingService fileProcessingService;
  private IndexingJobService indexingJobService;
  private IndexingRunEventRepository eventRepository;
  private StaleDocumentCleanupService cleanupService;
  private ConfluenceSyncStateRepository syncStateRepository;
  private ConfluenceIndexingExecutor executor;

  @BeforeAll
  static void start() {
    confluence = ConfluenceDataCenterFixture.get();
    properties = new ConfluenceProperties(25, null, null, 0, null, 0, 0, null, 0, null, null, 0);
    factory = new ConfluenceClientFactory(properties, TargetAddressValidator.disabled());
  }

  @BeforeEach
  void setUp() throws Exception {
    fileProcessingService = mock(FileProcessingService.class);
    when(fileProcessingService.processConfluencePage(any(), any(), any(), any(), any(), any()))
        .thenReturn(FileProcessingResult.PROCESSED);
    when(fileProcessingService.processUrlFile(
            any(), any(), any(), any(), anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(FileProcessingResult.PROCESSED);
    indexingJobService = mock(IndexingJobService.class);
    DocumentRepository documentRepository = mock(DocumentRepository.class);
    when(documentRepository.findByLibraryIdAndFilePath(any(), anyString()))
        .thenReturn(Optional.empty());
    eventRepository = mock(IndexingRunEventRepository.class);
    cleanupService = mock(StaleDocumentCleanupService.class);
    syncStateRepository = mock(ConfluenceSyncStateRepository.class);
    when(syncStateRepository.findByLibraryId(any())).thenReturn(Optional.empty());
    when(syncStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    executor =
        new ConfluenceIndexingExecutor(
            factory,
            properties,
            fileProcessingService,
            new AttachmentIndexer(
                new BoundedDownloader(TargetAddressValidator.disabled()),
                fileProcessingService,
                mock(LibraryStorageQuotaService.class),
                documentRepository),
            indexingJobService,
            documentRepository,
            eventRepository,
            mock(LibraryStorageQuotaService.class),
            cleanupService,
            syncStateRepository,
            mock(VectorChunkStore.class),
            Clock.systemUTC());
  }

  private KnowledgeLibrary library(String token, String... spaceKeys) {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Wiki " + UUID.randomUUID(),
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.CONFLUENCE,
            null,
            confluence.baseUrl(),
            null,
            token,
            false);
    library.configureConfluence(
        ConfluenceEdition.DATA_CENTER,
        java.util.Arrays.stream(spaceKeys)
            .map(k -> new ConfluenceSpaceSelection(k, null))
            .toList());
    return library;
  }

  private String pagePath(String title) {
    return confluence.baseUrl() + "/pages/viewpage.action?pageId=" + confluence.pageId(title);
  }

  @Test
  void aFullSyncTakesEveryReadablePageAndAttachmentAndLeavesTheTrashAndTheRestrictedPageOut()
      throws Exception {
    KnowledgeLibrary library = library(confluence.limitedToken(), "ENG", "HR");
    UUID jobId = UUID.randomUUID();

    executor.execute(jobId, library, IndexingRunMode.FULL);

    verify(fileProcessingService)
        .processConfluencePage(
            argThat(text -> text.contains("Zuständigkeiten") && text.contains("Bauantrag")),
            eq("Abschnitt 1.1"),
            eq(pagePath("Abschnitt 1.1")),
            eq("1"),
            eq(new SourceDocumentContext("ENG", "Handbuch / Kapitel 1")),
            eq(library));
    verify(fileProcessingService, never())
        .processConfluencePage(any(), eq("Nur Admin"), any(), any(), any(), any());
    verify(fileProcessingService, never())
        .processConfluencePage(any(), eq("Alt"), any(), any(), any(), any());
    verify(fileProcessingService)
        .processUrlFile(
            any(),
            eq("notizen.txt"),
            argThat(path -> path.endsWith("/notizen.txt")),
            eq("1"),
            anyLong(),
            eq(library),
            eq(DocumentSourceType.CONFLUENCE),
            eq(pagePath("Abschnitt 1.1")),
            any(),
            argThat(
                access ->
                    new SourceDocumentContext("ENG", "Handbuch / Kapitel 1 / Abschnitt 1.1")
                        .equals(access.sourceContext())));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Set<String>> current = ArgumentCaptor.forClass(Set.class);
    verify(cleanupService)
        .cleanupVanished(
            eq(library),
            eq(DocumentSourceType.CONFLUENCE),
            current.capture(),
            any(),
            eq(executor),
            eq(IndexingRunMode.FULL));
    assertThat(current.getValue())
        .contains(pagePath("Handbuch"), pagePath("Abschnitt 1.1"), pagePath("Onboarding"))
        .doesNotContain(pagePath("Nur Admin"), pagePath("Streng geheim"))
        .doesNotContain(
            confluence.baseUrl() + "/pages/viewpage.action?pageId=" + confluence.trashedPageId());
    verify(indexingJobService).completeJob(eq(jobId), anyInt(), eq(0), anyInt(), anyInt());
    verify(indexingJobService, never()).failJob(any(), any());
  }

  @Test
  void twoLibrariesAgainstTheSameInstanceReconcileOnlyTheirOwnSelectionAndToken() throws Exception {
    KnowledgeLibrary limited = library(confluence.limitedToken(), "ENG");
    KnowledgeLibrary admin = library(confluence.adminToken(), "SEC");

    executor.execute(UUID.randomUUID(), limited, IndexingRunMode.FULL);
    executor.execute(UUID.randomUUID(), admin, IndexingRunMode.FULL);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Set<String>> limitedSet = ArgumentCaptor.forClass(Set.class);
    verify(cleanupService)
        .cleanupVanished(eq(limited), any(), limitedSet.capture(), any(), any(), any());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Set<String>> adminSet = ArgumentCaptor.forClass(Set.class);
    verify(cleanupService)
        .cleanupVanished(eq(admin), any(), adminSet.capture(), any(), any(), any());

    assertThat(limitedSet.getValue())
        .contains(pagePath("Handbuch"))
        .doesNotContain(pagePath("Streng geheim"), pagePath("Nur Admin"));
    assertThat(adminSet.getValue())
        .contains(pagePath("Streng geheim"))
        .doesNotContain(pagePath("Handbuch"));
  }

  @Test
  void aSpaceTheTokenCannotReadIsReportedAndBlocksTheReconciliation() throws Exception {
    KnowledgeLibrary library = library(confluence.limitedToken(), "ENG", "SEC");
    UUID jobId = UUID.randomUUID();

    executor.execute(jobId, library, IndexingRunMode.FULL);

    verify(eventRepository)
        .save(
            argThat(
                (IndexingRunEvent event) ->
                    event.getCategory() == IndexingEventCategory.REJECTED
                        && "SEC".equals(event.getReference())));
    verify(cleanupService, never()).cleanupVanished(any(), any(), any(), any(), any(), any());
    verify(indexingJobService).completeJob(eq(jobId), anyInt(), eq(0), anyInt(), anyInt());
  }

  @Test
  void anIncrementalRunPicksUpAChangedPageAndRemovesNothing() throws Exception {
    // #1139 core scenario against the real instance: full run first (anchors the state), then a
    // change in Confluence, then an incremental run that takes it over and reconciles nothing.
    KnowledgeLibrary library = library(confluence.adminToken(), "HR");
    java.util.Map<UUID, io.opaa.indexing.source.confluence.ConfluenceSyncState> states =
        new java.util.HashMap<>();
    when(syncStateRepository.findByLibraryId(any()))
        .thenAnswer(inv -> Optional.ofNullable(states.get(inv.<UUID>getArgument(0))));
    when(syncStateRepository.save(any()))
        .thenAnswer(
            inv -> {
              io.opaa.indexing.source.confluence.ConfluenceSyncState s = inv.getArgument(0);
              states.put(s.getLibraryId(), s);
              return s;
            });
    executor.execute(UUID.randomUUID(), library, IndexingRunMode.FULL);
    assertThat(executor.defaultRunMode(library)).isEqualTo(IndexingRunMode.INCREMENTAL);
    org.mockito.Mockito.clearInvocations(fileProcessingService, cleanupService);

    confluence.updatePage("Onboarding", 2, "<p>Erste Schritte, aktualisiert am Tag zwei.</p>");
    // the search index catches up asynchronously (see ConfluenceDataCenterAccessTest): wait until
    // the change search actually lists the new version
    io.opaa.indexing.source.confluence.ConfluenceClient probe =
        factory.create(
            new io.opaa.indexing.source.confluence.ConfluenceConnection(
                java.net.URI.create(confluence.baseUrl()),
                ConfluenceEdition.DATA_CENTER,
                new io.opaa.indexing.source.confluence.ConfluenceCredentials
                    .DataCenterPersonalAccessToken(confluence.adminToken()),
                null,
                -1,
                false));
    long deadline = System.currentTimeMillis() + 60_000;
    while (System.currentTimeMillis() < deadline
        && probe
            .searchPagesModifiedSince(Set.of("HR"), java.time.Instant.now().minusSeconds(600))
            .stream()
            .noneMatch(p -> p.id().equals(confluence.pageId("Onboarding")) && p.version() >= 2)) {
      Thread.sleep(3000);
    }

    UUID jobId = UUID.randomUUID();
    executor.execute(jobId, library, IndexingRunMode.INCREMENTAL);

    verify(fileProcessingService)
        .processConfluencePage(
            argThat(text -> text.contains("aktualisiert am Tag zwei")),
            eq("Onboarding"),
            eq(pagePath("Onboarding")),
            eq("2"),
            any(),
            eq(library));
    verify(cleanupService, never()).cleanupVanished(any(), any(), any(), any(), any(), any());
    verify(indexingJobService, never()).failJob(eq(jobId), any());
  }

  @Test
  void aRevokedTokenFailsTheRunBeforeAnyListing() throws Exception {
    KnowledgeLibrary library = library("kein-gueltiges-token", "ENG");
    UUID jobId = UUID.randomUUID();

    executor.execute(jobId, library, IndexingRunMode.FULL);

    ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
    verify(indexingJobService).failJob(eq(jobId), message.capture());
    assertThat(message.getValue()).contains("anonym").doesNotContain("kein-gueltiges-token");
    verify(fileProcessingService, never())
        .processConfluencePage(any(), any(), any(), any(), any(), any());
    verify(cleanupService, never()).cleanupVanished(any(), any(), any(), any(), any(), any());
  }
}
