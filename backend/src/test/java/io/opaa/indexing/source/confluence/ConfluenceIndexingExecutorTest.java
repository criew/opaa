package io.opaa.indexing.source.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ConfluenceEdition;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.indexing.Document;
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
import io.opaa.library.ConfluenceSpaceSelection;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;

/**
 * The full sync (#1136) end to end against {@link FakeConfluenceServer}, for both editions: the
 * access layer is real, everything behind it (processing, job bookkeeping, repositories, the
 * reconciliation) is mocked and asserted on. Mirrors {@code UrlIndexingExecutorExecuteTest}'s
 * pattern; {@code execute} is called directly, so no {@code timeout()} is needed for the
 * assertions, only for the asynchronous habit's sake.
 */
class ConfluenceIndexingExecutorTest {

  private static final Instant NOW = Instant.parse("2026-09-03T10:00:00Z");
  private static final String EMAIL = "dienst@behoerde.example";
  private static final String TOKEN = "geheimes-token";

  private FakeConfluenceServer server;
  private FileProcessingService fileProcessingService;
  private IndexingJobService indexingJobService;
  private DocumentRepository documentRepository;
  private IndexingRunEventRepository eventRepository;
  private LibraryStorageQuotaService storageQuotaService;
  private StaleDocumentCleanupService cleanupService;
  private ConfluenceSyncStateRepository syncStateRepository;
  private VectorChunkStore vectorChunkStore;
  private final List<Duration> sleeps = new ArrayList<>();
  private ConfluenceIndexingExecutor executor;
  private KnowledgeLibrary library;
  private UUID jobId;

  static Stream<ConfluenceEdition> editions() {
    return Stream.of(ConfluenceEdition.CLOUD, ConfluenceEdition.DATA_CENTER);
  }

  @BeforeEach
  void setUp() throws Exception {
    fileProcessingService = mock(FileProcessingService.class);
    when(fileProcessingService.processUrlFile(
            any(), any(), any(), any(), anyLong(), any(), any(), any(), any()))
        .thenReturn(FileProcessingResult.PROCESSED);
    indexingJobService = mock(IndexingJobService.class);
    documentRepository = mock(DocumentRepository.class);
    when(documentRepository.findByLibraryIdAndFilePath(any(), anyString()))
        .thenReturn(Optional.empty());
    when(documentRepository.findByLibraryIdAndSourceEntryUrl(any(), anyString()))
        .thenReturn(List.of());
    eventRepository = mock(IndexingRunEventRepository.class);
    storageQuotaService = mock(LibraryStorageQuotaService.class);
    when(storageQuotaService.quotaExceededMessage(any()))
        .thenReturn("Speicherkontingent erschöpft");
    cleanupService = mock(StaleDocumentCleanupService.class);
    syncStateRepository = mock(ConfluenceSyncStateRepository.class);
    when(syncStateRepository.findByLibraryId(any())).thenReturn(Optional.empty());
    when(syncStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    vectorChunkStore = mock(VectorChunkStore.class);
    when(fileProcessingService.processConfluencePage(any(), any(), any(), any(), any(), any()))
        .thenReturn(FileProcessingResult.PROCESSED);
    jobId = UUID.randomUUID();
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.close();
    }
  }

  private void start(ConfluenceEdition edition, Set<String> readableSpaces, String... spaceKeys)
      throws Exception {
    server = new FakeConfluenceServer(edition);
    server.addSpace("1", "ENG", "Engineering");
    server.addSpace("2", "HR", "Personal");
    server.addSpace("3", "SEC", "Geheimschutz");
    server.addPage("100", "ENG", "Handbuch", null, "<p>Willkommen im Handbuch.</p>", NOW);
    server.addPage("101", "ENG", "Kapitel 1", "100", "<p>Das erste Kapitel.</p>", NOW);
    server.addPage(
        "102",
        "ENG",
        "Abschnitt 1.1",
        "101",
        "<h1>Zuständigkeiten</h1><p>Das Bauamt bearbeitet Anträge innerhalb von 14 Tagen.</p>",
        NOW);
    server.addAttachment(
        "900",
        "102",
        "notizen.txt",
        "text/plain",
        "Notizen zur Sitzung".getBytes(StandardCharsets.UTF_8));
    server.addPage("200", "HR", "Onboarding", null, "<p>Erste Schritte.</p>", NOW);
    server.addPage("300", "SEC", "Streng geheim", null, "<p>Nicht für alle.</p>", NOW);
    ConfluenceCredentials credentials = server.addToken(EMAIL, TOKEN, readableSpaces);
    String stored =
        credentials instanceof ConfluenceCredentials.CloudApiToken ? EMAIL + ":" + TOKEN : TOKEN;
    library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Wiki",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.CONFLUENCE,
            null,
            server.baseUrl(),
            null,
            stored,
            false);
    List<ConfluenceSpaceSelection> selection = new ArrayList<>();
    for (String key : spaceKeys) {
      selection.add(new ConfluenceSpaceSelection(key, null));
    }
    library.configureConfluence(edition, selection);
    ConfluenceClientFactory factory =
        new ConfluenceClientFactory(
            new ConfluenceProperties(2, null, null, 3, Duration.ofSeconds(2), 0, 0, null, 0),
            TargetAddressValidator.disabled(),
            sleeps::add);
    executor =
        new ConfluenceIndexingExecutor(
            factory,
            fileProcessingService,
            indexingJobService,
            documentRepository,
            eventRepository,
            storageQuotaService,
            cleanupService,
            syncStateRepository,
            vectorChunkStore,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private String pagePath(ConfluenceEdition edition, String spaceKey, String id) {
    return edition == ConfluenceEdition.CLOUD
        ? server.baseUrl() + "/wiki/spaces/" + spaceKey + "/pages/" + id
        : server.baseUrl() + "/pages/viewpage.action?pageId=" + id;
  }

  @ParameterizedTest
  @MethodSource("editions")
  void indexesEverySelectedPageAndAttachmentWithItsContextAndReconcilesTheBestand(
      ConfluenceEdition edition) throws Exception {
    start(edition, null, "ENG", "HR");

    executor.execute(jobId, library, IndexingRunMode.FULL);

    String abschnitt = pagePath(edition, "ENG", "102");
    verify(fileProcessingService)
        .processConfluencePage(
            contains("Zuständigkeiten"),
            eq("Abschnitt 1.1"),
            eq(abschnitt),
            eq("1"),
            eq(new SourceDocumentContext("ENG", "Handbuch / Kapitel 1")),
            eq(library));
    verify(fileProcessingService)
        .processConfluencePage(
            any(),
            eq("Onboarding"),
            eq(pagePath(edition, "HR", "200")),
            eq("1"),
            eq(new SourceDocumentContext("HR", null)),
            eq(library));
    verify(fileProcessingService, never())
        .processConfluencePage(any(), eq("Streng geheim"), any(), any(), any(), any());
    ArgumentCaptor<String> attachmentPath = ArgumentCaptor.forClass(String.class);
    verify(fileProcessingService)
        .processUrlFile(
            any(),
            eq("notizen.txt"),
            attachmentPath.capture(),
            eq("1"),
            eq(19L),
            eq(library),
            eq(DocumentSourceType.CONFLUENCE),
            eq(abschnitt),
            eq(new SourceDocumentContext("ENG", "Handbuch / Kapitel 1 / Abschnitt 1.1")));
    assertThat(attachmentPath.getValue()).doesNotContain("?").contains("notizen.txt");

    // the listing carried identifiers and versions only - every body was fetched individually
    assertThat(server.requests())
        .noneMatch(r -> (r.contains("/pages?") || r.contains("/content?")) && r.contains("body"));
    assertThat(server.requests()).filteredOn(r -> r.contains("/102")).isNotEmpty();

    // every page and attachment met is the bestand the reconciliation compares against
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Set<String>> current = ArgumentCaptor.forClass(Set.class);
    verify(cleanupService, timeout(5000))
        .cleanupVanished(
            eq(library),
            eq(DocumentSourceType.CONFLUENCE),
            current.capture(),
            any(),
            eq(executor),
            eq(IndexingRunMode.FULL));
    assertThat(current.getValue())
        .containsExactlyInAnyOrder(
            pagePath(edition, "ENG", "100"),
            pagePath(edition, "ENG", "101"),
            abschnitt,
            pagePath(edition, "HR", "200"),
            attachmentPath.getValue());
    verify(indexingJobService).completeJob(jobId, 4, 0, 0, 5);

    ArgumentCaptor<ConfluenceSyncState> state = ArgumentCaptor.forClass(ConfluenceSyncState.class);
    verify(syncStateRepository, timeout(5000).atLeast(2)).save(state.capture());
    ConfluenceSyncState finalState = state.getValue();
    assertThat(finalState.getFullSyncCompletedAt()).isNotNull();
    assertThat(finalState.getIncrementalAnchor()).isEqualTo(NOW);
    assertThat(finalState.isFullSyncInterrupted()).isFalse();
  }

  @ParameterizedTest
  @MethodSource("editions")
  void anUnchangedVersionIsSkippedBeforeAnyBodyFetchButItsAttachmentsAreStillListed(
      ConfluenceEdition edition) throws Exception {
    start(edition, null, "ENG");
    String abschnitt = pagePath(edition, "ENG", "102");
    Document indexed =
        new Document("Abschnitt 1.1", abschnitt, "text/html", 10L, DocumentSourceType.CONFLUENCE);
    indexed.setStatus(DocumentStatus.INDEXED);
    indexed.setLastModifiedRemote("1");
    when(documentRepository.findByLibraryIdAndFilePath(library.getId(), abschnitt))
        .thenReturn(Optional.of(indexed));

    executor.execute(jobId, library, IndexingRunMode.FULL);

    verify(fileProcessingService, never())
        .processConfluencePage(any(), eq("Abschnitt 1.1"), any(), any(), any(), any());
    assertThat(server.requests())
        .as("no body fetch for the unchanged page")
        .noneMatch(r -> r.matches(".*/(content|pages)/102(\\?.*)?$"));
    verify(fileProcessingService)
        .processUrlFile(
            any(), eq("notizen.txt"), any(), any(), anyLong(), any(), any(), eq(abschnitt), any());
    verify(indexingJobService).completeJob(jobId, 2, 0, 1, 3);
  }

  @ParameterizedTest
  @MethodSource("editions")
  void aPageTheTokenCannotFetchIsSkippedVisiblyAndStaysInTheBestand(ConfluenceEdition edition)
      throws Exception {
    start(edition, null, "ENG");
    server.hideFromFetch("101");

    executor.execute(jobId, library, IndexingRunMode.FULL);

    String kapitel = pagePath(edition, "ENG", "101");
    // #1138: the protocol names space and title, not just a URL
    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.REJECTED,
                    "Seite „Kapitel 1“ (Space ENG) "
                        + ConfluenceIndexingExecutor.UNREADABLE_PAGE_MESSAGE,
                    kapitel)));
    verify(fileProcessingService, never())
        .processConfluencePage(any(), eq("Kapitel 1"), any(), any(), any(), any());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Set<String>> current = ArgumentCaptor.forClass(Set.class);
    verify(cleanupService).cleanupVanished(any(), any(), current.capture(), any(), any(), any());
    assertThat(current.getValue()).as("a 404 is no deletion finding").contains(kapitel);
    verify(indexingJobService).completeJob(jobId, 2, 0, 1, 3);
  }

  @ParameterizedTest
  @MethodSource("editions")
  void aSpaceTheTokenCannotListIsReportedAndNothingIsReconciled(ConfluenceEdition edition)
      throws Exception {
    start(edition, Set.of("ENG", "HR"), "ENG", "SEC");

    executor.execute(jobId, library, IndexingRunMode.FULL);

    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.REJECTED,
                    "Space SEC " + ConfluenceIndexingExecutor.UNREADABLE_SPACE_MESSAGE,
                    "SEC")));
    verify(cleanupService, never()).cleanupVanished(any(), any(), any(), any(), any(), any());
    verify(indexingJobService).completeJob(jobId, 3, 0, 0, 4);
    ArgumentCaptor<ConfluenceSyncState> state = ArgumentCaptor.forClass(ConfluenceSyncState.class);
    verify(syncStateRepository, timeout(5000).atLeast(1)).save(state.capture());
    assertThat(state.getValue().isFullSyncInterrupted())
        .as("an incomplete listing leaves the full sync open for the next run")
        .isTrue();
    assertThat(state.getValue().completedSpaceKeys()).containsExactly("ENG");
  }

  @ParameterizedTest
  @MethodSource("editions")
  void aDeselectedSpaceAndAVanishedPageFallOutOfTheReconciliationSet(ConfluenceEdition edition)
      throws Exception {
    start(edition, null, "ENG");
    server.trashPage("101");

    executor.execute(jobId, library, IndexingRunMode.FULL);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Set<String>> current = ArgumentCaptor.forClass(Set.class);
    verify(cleanupService).cleanupVanished(any(), any(), current.capture(), any(), any(), any());
    assertThat(current.getValue())
        .doesNotContain(pagePath(edition, "ENG", "101"), pagePath(edition, "HR", "200"))
        .contains(pagePath(edition, "ENG", "100"), pagePath(edition, "ENG", "102"));
  }

  @ParameterizedTest
  @MethodSource("editions")
  void rateLimitingSlowsTheRunDownAndIsReportedOnce(ConfluenceEdition edition) throws Exception {
    start(edition, null, "HR");
    server.throttleNext(2, "1");

    executor.execute(jobId, library, IndexingRunMode.FULL);

    assertThat(sleeps).hasSize(2);
    verify(indexingJobService).completeJob(jobId, 1, 0, 0, 1);
    verify(eventRepository)
        .save(argThat(event(IndexingEventCategory.RATE_LIMITED, "2-mal gedrosselt", null)));
  }

  @ParameterizedTest
  @MethodSource("editions")
  void rejectedCredentialsFailTheRunBeforeAnyListing(ConfluenceEdition edition) throws Exception {
    start(edition, null, "ENG");
    library =
        KnowledgeLibrary.ownedByUser(
            library.getOrganizationId(),
            "Wiki",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.CONFLUENCE,
            null,
            server.baseUrl(),
            null,
            edition == ConfluenceEdition.CLOUD ? EMAIL + ":falsch" : "falsch",
            false);
    library.configureConfluence(edition, List.of(new ConfluenceSpaceSelection("ENG", null)));

    executor.execute(jobId, library, IndexingRunMode.FULL);

    ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
    verify(indexingJobService).failJob(eq(jobId), message.capture());
    assertThat(message.getValue()).doesNotContain("falsch").doesNotContain(TOKEN);
    assertThat(server.requests())
        .as("no listing with a token the instance did not accept")
        .noneMatch(r -> r.contains("/pages?") || r.contains("/content?"));
    verify(cleanupService, never()).cleanupVanished(any(), any(), any(), any(), any(), any());
    verify(indexingJobService, never()).completeJob(any(), anyInt(), anyInt(), anyInt(), anyInt());
  }

  @ParameterizedTest
  @MethodSource("editions")
  void anInterruptedFullSyncResumesWithTheUnfinishedSpacesFirst(ConfluenceEdition edition)
      throws Exception {
    start(edition, null, "ENG", "HR");
    ConfluenceSyncState interrupted = new ConfluenceSyncState(library.getId());
    interrupted.beginFullSync(UUID.randomUUID());
    interrupted.markSpaceCompleted("ENG");
    when(syncStateRepository.findByLibraryId(library.getId())).thenReturn(Optional.of(interrupted));

    executor.execute(jobId, library, IndexingRunMode.FULL);

    List<String> listings =
        server.requests().stream()
            .filter(r -> r.contains("/pages?") || r.contains("/content?"))
            .toList();
    assertThat(listings).isNotEmpty();
    // Cloud lists by space id (HR is space 2), Data Center by key
    assertThat(listings.getFirst())
        .as("HR was unfinished, so it goes first")
        .matches(r -> r.contains("/spaces/2/pages") || r.contains("spaceKey=HR"));
    verify(indexingJobService).completeJob(jobId, 4, 0, 0, 5);
    assertThat(interrupted.isFullSyncInterrupted()).isFalse();
  }

  @ParameterizedTest
  @MethodSource("editions")
  void anExhaustedQuotaIsReportedAsRejectedNotAsFailure(ConfluenceEdition edition)
      throws Exception {
    start(edition, null, "HR");
    when(fileProcessingService.processConfluencePage(any(), any(), any(), any(), any(), any()))
        .thenReturn(FileProcessingResult.QUOTA_EXCEEDED);

    executor.execute(jobId, library, IndexingRunMode.FULL);

    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.REJECTED,
                    "Speicherkontingent erschöpft",
                    pagePath(edition, "HR", "200"))));
    verify(indexingJobService).completeJob(jobId, 0, 0, 1, 0);
  }

  @ParameterizedTest
  @MethodSource("editions")
  void anUndeclaredRunModeFailsTheRunWithoutTouchingTheInstance(ConfluenceEdition edition)
      throws Exception {
    start(edition, null, "ENG");

    executor.execute(jobId, library, IndexingRunMode.INCREMENTAL);

    verify(indexingJobService).failJob(eq(jobId), contains("INCREMENTAL"));
    assertThat(server.requests()).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("editions")
  void attachmentsOfAPageThisRunCouldNotProcessStayInTheReconciliationSet(ConfluenceEdition edition)
      throws Exception {
    // #1179 review, CRITICAL: a page that cannot be fetched (404) or stored (quota) is no finding
    // about its attachments - their known documents must not look vanished to the cleanup.
    start(edition, null, "ENG");
    String abschnitt = pagePath(edition, "ENG", "102");
    Document knownAttachment =
        new Document(
            "notizen.txt",
            server.baseUrl() + "/download/attachments/102/notizen.txt",
            "text/plain",
            19L,
            DocumentSourceType.CONFLUENCE);
    knownAttachment.setStatus(DocumentStatus.INDEXED);
    when(documentRepository.findByLibraryIdAndSourceEntryUrl(library.getId(), abschnitt))
        .thenReturn(List.of(knownAttachment));
    server.hideFromFetch("102");

    executor.execute(jobId, library, IndexingRunMode.FULL);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Set<String>> current = ArgumentCaptor.forClass(Set.class);
    verify(cleanupService).cleanupVanished(any(), any(), current.capture(), any(), any(), any());
    assertThat(current.getValue()).contains(abschnitt, knownAttachment.getFilePath());
  }

  @ParameterizedTest
  @MethodSource("editions")
  void anExhaustedQuotaKeepsThePagesKnownAttachmentsAsWell(ConfluenceEdition edition)
      throws Exception {
    start(edition, null, "ENG");
    String abschnitt = pagePath(edition, "ENG", "102");
    Document knownAttachment =
        new Document(
            "notizen.txt",
            server.baseUrl() + "/download/attachments/102/notizen.txt",
            "text/plain",
            19L,
            DocumentSourceType.CONFLUENCE);
    when(documentRepository.findByLibraryIdAndSourceEntryUrl(library.getId(), abschnitt))
        .thenReturn(List.of(knownAttachment));
    when(fileProcessingService.processConfluencePage(
            any(), eq("Abschnitt 1.1"), any(), any(), any(), any()))
        .thenReturn(FileProcessingResult.QUOTA_EXCEEDED);

    executor.execute(jobId, library, IndexingRunMode.FULL);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Set<String>> current = ArgumentCaptor.forClass(Set.class);
    verify(cleanupService).cleanupVanished(any(), any(), current.capture(), any(), any(), any());
    assertThat(current.getValue()).contains(knownAttachment.getFilePath());
    verify(fileProcessingService, never())
        .processUrlFile(
            any(), eq("notizen.txt"), any(), any(), anyLong(), any(), any(), any(), any());
  }

  @ParameterizedTest
  @MethodSource("editions")
  void aFailedReconciliationLeavesTheFullSyncOpenAndSaysSo(ConfluenceEdition edition)
      throws Exception {
    start(edition, null, "HR");
    when(cleanupService.cleanupVanished(any(), any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("Datenbank nicht erreichbar"));

    executor.execute(jobId, library, IndexingRunMode.FULL);

    verify(eventRepository)
        .save(
            argThat(
                event(IndexingEventCategory.ERROR, "Abgleich des Bestands fehlgeschlagen", null)));
    ArgumentCaptor<ConfluenceSyncState> state = ArgumentCaptor.forClass(ConfluenceSyncState.class);
    verify(syncStateRepository, timeout(5000).atLeast(1)).save(state.capture());
    assertThat(state.getValue().isFullSyncInterrupted()).isTrue();
    assertThat(state.getValue().getIncrementalAnchor()).isNull();
    verify(indexingJobService).completeJob(jobId, 1, 0, 0, 1);
  }

  @ParameterizedTest
  @MethodSource("editions")
  void throttlingIsReportedEvenWhenTheRunFailsAfterwards(ConfluenceEdition edition)
      throws Exception {
    start(edition, null, "ENG");
    library =
        KnowledgeLibrary.ownedByUser(
            library.getOrganizationId(),
            "Wiki",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.CONFLUENCE,
            null,
            server.baseUrl(),
            null,
            edition == ConfluenceEdition.CLOUD ? EMAIL + ":falsch" : "falsch",
            false);
    library.configureConfluence(edition, List.of(new ConfluenceSpaceSelection("ENG", null)));
    server.throttleNext(1, "1");

    executor.execute(jobId, library, IndexingRunMode.FULL);

    verify(indexingJobService).failJob(eq(jobId), any());
    verify(eventRepository)
        .save(argThat(event(IndexingEventCategory.RATE_LIMITED, "1-mal gedrosselt", null)));
  }

  private static ArgumentMatcher<IndexingRunEvent> event(
      IndexingEventCategory category, String messagePart, String reference) {
    return event ->
        event.getCategory() == category
            && event.getMessage().contains(messagePart)
            && (reference == null || reference.equals(event.getReference()));
  }
}
