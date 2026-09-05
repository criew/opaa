package io.opaa.indexing.source.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
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
import io.opaa.indexing.IndexingRunCost;
import io.opaa.indexing.IndexingRunEvent;
import io.opaa.indexing.IndexingRunEventRepository;
import io.opaa.indexing.SourceDocumentContext;
import io.opaa.indexing.StaleDocumentCleanupService;
import io.opaa.indexing.VectorChunkStore;
import io.opaa.indexing.source.attachment.AttachmentAccess;
import io.opaa.indexing.source.attachment.AttachmentIndexer;
import io.opaa.library.ConfluenceSpaceSelection;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.sourceaccess.BoundedDownloader;
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
 * The full sync end to end against {@link FakeConfluenceServer}, for both editions: the access
 * layer and the generalized attachment path ({@link AttachmentIndexer}) are real, everything behind
 * them (processing, job bookkeeping, repositories, the reconciliation) is mocked and asserted on.
 * Mirrors {@code UrlIndexingExecutorExecuteTest}'s pattern; {@code execute} is called directly, so
 * no {@code timeout()} is needed for the assertions, only for the asynchronous habit's sake.
 */
class ConfluenceIndexingExecutorTest {

  private static final Instant NOW = Instant.parse("2026-09-03T10:00:00Z");

  /**
   * When the seeded pages were last modified - well before any incremental anchor in these tests.
   */
  private static final Instant SEEDED_AT = NOW.minus(Duration.ofDays(3));

  private static final Duration FULL_SYNC_INTERVAL = Duration.ofDays(7);
  private static final Duration OVERLAP = Duration.ofMinutes(10);
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

  /**
   * Every page {@code processConfluencePage} stored this test - the default {@code
   * findByLibraryIdAndFilePath} answers from it, so the executor finds the page row an attachment
   * becomes a child of.
   */
  private final List<Document> storedPages = new ArrayList<>();

  /** the request budget the executor under test runs with; 0 (the default) is unbounded. */
  private int requestBudget;

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
            any(), any(), any(), any(), anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(FileProcessingResult.PROCESSED);
    indexingJobService = mock(IndexingJobService.class);
    documentRepository = mock(DocumentRepository.class);
    when(documentRepository.findByLibraryIdAndFilePath(any(), anyString()))
        .thenAnswer(
            inv ->
                storedPages.stream()
                    .filter(d -> d.getFilePath().equals(inv.getArgument(1)))
                    .findFirst());
    // Mockito invokes the stubbed method with null arguments while a test re-stubs it - such a
    // call must not leave a page without a path behind.
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
        .thenAnswer(
            inv -> {
              if (inv.getArgument(2) != null) {
                storedPages.add(
                    storedPage(
                        inv.getArgument(1),
                        inv.getArgument(2),
                        inv.getArgument(3),
                        inv.getArgument(4)));
              }
              return FileProcessingResult.PROCESSED;
            });
    jobId = UUID.randomUUID();
  }

  private static Document storedPage(
      String title, String path, String version, SourceDocumentContext context) {
    Document doc = new Document(title, path, "text/html", 10L, DocumentSourceType.CONFLUENCE);
    doc.setStatus(DocumentStatus.INDEXED);
    doc.setLastModifiedRemote(version);
    doc.applySourceContext(context);
    return doc;
  }

  private Document storedPage(String path) {
    return storedPages.stream()
        .filter(d -> d.getFilePath().equals(path))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no page stored under " + path));
  }

  /** The {@link AttachmentAccess} carries the page's context to every attachment. */
  private static AttachmentAccess withContext(SourceDocumentContext context) {
    return argThat(access -> access != null && context.equals(access.sourceContext()));
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
    server.addPage("100", "ENG", "Handbuch", null, "<p>Willkommen im Handbuch.</p>", SEEDED_AT);
    server.addPage("101", "ENG", "Kapitel 1", "100", "<p>Das erste Kapitel.</p>", SEEDED_AT);
    server.addPage(
        "102",
        "ENG",
        "Abschnitt 1.1",
        "101",
        "<h1>Zuständigkeiten</h1><p>Das Bauamt bearbeitet Anträge innerhalb von 14 Tagen.</p>",
        SEEDED_AT);
    server.addAttachment(
        "900",
        "102",
        "notizen.txt",
        "text/plain",
        "Notizen zur Sitzung".getBytes(StandardCharsets.UTF_8));
    server.addPage("200", "HR", "Onboarding", null, "<p>Erste Schritte.</p>", SEEDED_AT);
    server.addPage("300", "SEC", "Streng geheim", null, "<p>Nicht für alle.</p>", SEEDED_AT);
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
    ConfluenceProperties properties =
        new ConfluenceProperties(
            2,
            null,
            null,
            3,
            Duration.ofSeconds(2),
            0,
            0,
            null,
            0,
            FULL_SYNC_INTERVAL,
            OVERLAP,
            requestBudget);
    ConfluenceClientFactory factory =
        new ConfluenceClientFactory(properties, TargetAddressValidator.disabled(), sleeps::add);
    executor =
        new ConfluenceIndexingExecutor(
            factory,
            properties,
            fileProcessingService,
            attachmentIndexer(),
            indexingJobService,
            documentRepository,
            eventRepository,
            storageQuotaService,
            cleanupService,
            syncStateRepository,
            vectorChunkStore,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  /** The real generalized attachment path over the mocked processing. */
  private AttachmentIndexer attachmentIndexer() {
    return new AttachmentIndexer(
        new BoundedDownloader(TargetAddressValidator.disabled()),
        fileProcessingService,
        storageQuotaService,
        new io.opaa.indexing.source.attachment.AttachmentProperties(5));
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
    // ADR-0022: the attachment goes the generalized path - a child of the page's own row,
    // with the page's place as its context and the version as its change marker
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
            eq(storedPage(abschnitt).getId()),
            withContext(new SourceDocumentContext("ENG", "Handbuch / Kapitel 1 / Abschnitt 1.1")));
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
    // a complete listing records a positive assessment, clearing any earlier warning
    verify(indexingJobService).recordListingAssessment(jobId, true, List.of());

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
            any(),
            eq("notizen.txt"),
            any(),
            any(),
            anyLong(),
            any(),
            any(),
            eq(abschnitt),
            eq(indexed.getId()),
            any());
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
    // the protocol names space and title, not just a URL
    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.REJECTED,
                    "Seite „Kapitel 1“ (Space ENG) "
                        + ConfluenceIndexingExecutor.UNREADABLE_PAGE_SUFFIX,
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
                    "Space SEC " + ConfluenceIndexingExecutor.UNREADABLE_SPACE_SUFFIX,
                    "SEC")));
    verify(cleanupService, never()).cleanupVanished(any(), any(), any(), any(), any(), any());
    verify(indexingJobService).completeJob(jobId, 3, 0, 0, 4);
    // the run's assessment names the unreadable space, for the warning at the library
    verify(indexingJobService).recordListingAssessment(jobId, false, List.of("SEC"));
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
  void attachmentsOfAPageThisRunCouldNotProcessStayInTheReconciliationSet(ConfluenceEdition edition)
      throws Exception {
    // a page that cannot be fetched (404) or stored (quota) is no finding
    // about its attachments - their known documents must not look vanished to the cleanup. The
    // attachments hang on the page by parent_document_id and are preserved from the
    // database (ADR-0022, Entscheidung 3), the attachment of an attachment included.
    start(edition, null, "ENG");
    String abschnitt = pagePath(edition, "ENG", "102");
    Document knownPage =
        new Document("Abschnitt 1.1", abschnitt, "text/html", 10L, DocumentSourceType.CONFLUENCE);
    Document knownAttachment =
        confluenceAttachment(
            "notizen.eml", server.baseUrl() + "/download/attachments/102/notizen.eml", knownPage);
    Document nestedAttachment =
        confluenceAttachment(
            "anlage.pdf", knownAttachment.getFilePath() + "/0/anlage.pdf", knownAttachment);
    when(documentRepository.findByLibraryIdAndSourceType(
            library.getId(), DocumentSourceType.CONFLUENCE))
        .thenReturn(List.of(knownPage, knownAttachment, nestedAttachment));
    server.hideFromFetch("102");

    executor.execute(jobId, library, IndexingRunMode.FULL);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Set<String>> current = ArgumentCaptor.forClass(Set.class);
    verify(cleanupService).cleanupVanished(any(), any(), current.capture(), any(), any(), any());
    assertThat(current.getValue())
        .contains(abschnitt, knownAttachment.getFilePath(), nestedAttachment.getFilePath());
  }

  private Document confluenceAttachment(String fileName, String filePath, Document parent) {
    Document attachment =
        new Document(
            fileName, filePath, "application/octet-stream", 5L, DocumentSourceType.CONFLUENCE);
    attachment.setStatus(DocumentStatus.INDEXED);
    attachment.setLibraryId(library.getId());
    attachment.setParentDocumentId(parent.getId());
    return attachment;
  }

  @ParameterizedTest
  @MethodSource("editions")
  void anExhaustedQuotaKeepsThePagesKnownAttachmentsAsWell(ConfluenceEdition edition)
      throws Exception {
    start(edition, null, "ENG");
    String abschnitt = pagePath(edition, "ENG", "102");
    Document knownPage =
        new Document("Abschnitt 1.1", abschnitt, "text/html", 10L, DocumentSourceType.CONFLUENCE);
    Document knownAttachment =
        confluenceAttachment(
            "notizen.txt", server.baseUrl() + "/download/attachments/102/notizen.txt", knownPage);
    when(documentRepository.findByLibraryIdAndSourceType(
            library.getId(), DocumentSourceType.CONFLUENCE))
        .thenReturn(List.of(knownPage, knownAttachment));
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
            any(), eq("notizen.txt"), any(), any(), anyLong(), any(), any(), any(), any(), any());
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

  @org.junit.jupiter.api.Test
  void bothEditionsHandTheSamePageBodyToTheSamePipeline() throws Exception {
    // the preparation works on the storage body, which both adapters deliver alike - the
    // pipeline behind processConfluencePage therefore sees the same input for Cloud and Data Center
    java.util.Map<ConfluenceEdition, String> bodies =
        new java.util.EnumMap<>(ConfluenceEdition.class);
    for (ConfluenceEdition edition : editions().toList()) {
      start(edition, null, "ENG");
      executor.execute(UUID.randomUUID(), library, IndexingRunMode.FULL);
      ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
      verify(fileProcessingService)
          .processConfluencePage(
              body.capture(), eq("Abschnitt 1.1"), any(), any(), any(), eq(library));
      bodies.put(edition, body.getValue());
      server.close();
      server = null;
      org.mockito.Mockito.clearInvocations(fileProcessingService);
    }
    assertThat(bodies.get(ConfluenceEdition.CLOUD))
        .isEqualTo(bodies.get(ConfluenceEdition.DATA_CENTER))
        .contains("<h1>Zuständigkeiten</h1>");
  }

  @ParameterizedTest
  @MethodSource("editions")
  void anAttachmentOfAnUnsupportedTypeIsSkippedVisibly(ConfluenceEdition edition) throws Exception {
    start(edition, null, "ENG");
    server.addAttachment(
        "901",
        "102",
        "werkzeug.exe",
        "application/octet-stream",
        new byte[] {0x4d, 0x5a, 0, 0, 1, 2});

    executor.execute(jobId, library, IndexingRunMode.FULL);

    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.UNSUPPORTED_FORMAT,
                    "Anlagenformat wird nicht unterstützt",
                    null)));
    verify(fileProcessingService, never())
        .processUrlFile(
            any(), eq("werkzeug.exe"), any(), any(), anyLong(), any(), any(), any(), any(), any());
    // the unsupported attachment is still part of the bestand the reconciliation compares against
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Set<String>> current = ArgumentCaptor.forClass(Set.class);
    verify(cleanupService).cleanupVanished(any(), any(), current.capture(), any(), any(), any());
    assertThat(current.getValue()).anyMatch(path -> path.endsWith("/werkzeug.exe"));
  }

  // ---- incremental run ----------------------------------------------------------------

  private ConfluenceSyncState completedFullSync(Instant anchor) {
    ConfluenceSyncState state = new ConfluenceSyncState(library.getId());
    state.beginFullSync(UUID.randomUUID());
    state.completeFullSync(anchor, anchor);
    when(syncStateRepository.findByLibraryId(library.getId())).thenReturn(Optional.of(state));
    return state;
  }

  @ParameterizedTest
  @MethodSource("editions")
  void anIncrementalRunTakesTheChangedPagesOnlyAndNeverReconciles(ConfluenceEdition edition)
      throws Exception {
    start(edition, null, "ENG", "HR");
    ConfluenceSyncState state = completedFullSync(NOW.minus(Duration.ofHours(2)));
    // changed after the anchor (minus overlap): Kapitel 1 edited, Onboarding edited; Handbuch and
    // Abschnitt 1.1 are older and must not even be fetched
    server.updatePage(
        "101", "<p>Das erste Kapitel, überarbeitet.</p>", NOW.minus(Duration.ofHours(1)));
    server.updatePage("200", "<p>Erste Schritte, neu.</p>", NOW.minus(Duration.ofMinutes(30)));

    executor.execute(jobId, library, IndexingRunMode.INCREMENTAL);

    verify(fileProcessingService)
        .processConfluencePage(
            contains("überarbeitet"),
            eq("Kapitel 1"),
            eq(pagePath(edition, "ENG", "101")),
            eq("2"),
            eq(new SourceDocumentContext("ENG", "Handbuch")),
            eq(library));
    verify(fileProcessingService)
        .processConfluencePage(any(), eq("Onboarding"), any(), eq("2"), any(), eq(library));
    verify(fileProcessingService, never())
        .processConfluencePage(any(), eq("Handbuch"), any(), any(), any(), any());
    verify(fileProcessingService, never())
        .processConfluencePage(any(), eq("Abschnitt 1.1"), any(), any(), any(), any());
    // the change search asked for identifiers only, never for bodies
    assertThat(server.requests())
        .filteredOn(r -> r.contains("search"))
        .isNotEmpty()
        .noneMatch(r -> r.contains("body"));
    // "ergänzend": nothing is ever removed for being absent from the window
    verify(cleanupService, never()).cleanupVanished(any(), any(), any(), any(), any(), any());
    verify(indexingJobService).completeJob(jobId, 2, 0, 0, 2);
    // an incremental run cannot see an unreadable space and never assesses the listing
    verify(indexingJobService, never()).recordListingAssessment(any(), anyBoolean(), any());
    // the anchor moves to this run's start, not its end
    assertThat(state.getIncrementalAnchor()).isEqualTo(NOW);
    verify(syncStateRepository).save(state);
  }

  @ParameterizedTest
  @MethodSource("editions")
  void anIncrementalRunSearchesFromTheAnchorMinusTheOverlapAndSkipsUnchangedVersions(
      ConfluenceEdition edition) throws Exception {
    start(edition, null, "ENG");
    Instant anchor = NOW.minus(Duration.ofHours(1));
    completedFullSync(anchor);
    // modified inside the overlap window before the anchor: found again, but the version is known
    server.updatePage(
        "100", "<p>Willkommen, leicht geändert.</p>", anchor.minus(Duration.ofMinutes(5)));
    String handbuch = pagePath(edition, "ENG", "100");
    Document indexed =
        new Document("Handbuch", handbuch, "text/html", 10L, DocumentSourceType.CONFLUENCE);
    indexed.setStatus(DocumentStatus.INDEXED);
    indexed.setLastModifiedRemote("2");
    when(documentRepository.findByLibraryIdAndFilePath(library.getId(), handbuch))
        .thenReturn(Optional.of(indexed));

    executor.execute(jobId, library, IndexingRunMode.INCREMENTAL);

    // the window is relative to the instance's own clock and reaches back to anchor - overlap
    long expectedMinutes =
        Duration.between(anchor.minus(OVERLAP), Instant.now()).getSeconds() / 60 + 1;
    assertThat(searchWindowMinutes()).isBetween(expectedMinutes - 1, expectedMinutes + 1);
    // the version came with the search: known and unchanged, so the body is never fetched
    assertThat(server.requests()).noneMatch(r -> r.matches(".*/(content|pages)/100(\\?.*)?$"));
    verify(fileProcessingService, never())
        .processConfluencePage(any(), any(), any(), any(), any(), any());
    verify(indexingJobService).completeJob(jobId, 0, 0, 1, 0);
  }

  /** The minutes {@code N} of the {@code lastmodified >= now("-Nm")} clause the run sent. */
  private long searchWindowMinutes() {
    String search =
        server.requests().stream().filter(r -> r.contains("search")).findFirst().orElseThrow();
    String decoded = java.net.URLDecoder.decode(search, java.nio.charset.StandardCharsets.UTF_8);
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("now\\(\"-(\\d+)m\"\\)").matcher(decoded);
    assertThat(m.find()).as("relative window in %s", decoded).isTrue();
    return Long.parseLong(m.group(1));
  }

  @ParameterizedTest
  @MethodSource("editions")
  void aSmallerOverlapReachesLessFarBack(ConfluenceEdition edition) throws Exception {
    start(edition, null, "ENG");
    Instant anchor = NOW.minus(Duration.ofHours(1));
    completedFullSync(anchor);
    executor.execute(jobId, library, IndexingRunMode.INCREMENTAL);
    long withDefaultOverlap = searchWindowMinutes();

    server.requests().clear();
    // the first run advanced the anchor - start the comparison run from the same anchor
    completedFullSync(anchor);
    ConfluenceProperties smallOverlap =
        new ConfluenceProperties(
            2,
            null,
            null,
            3,
            Duration.ofSeconds(2),
            0,
            0,
            null,
            0,
            FULL_SYNC_INTERVAL,
            Duration.ofMinutes(1),
            0);
    executor =
        new ConfluenceIndexingExecutor(
            new ConfluenceClientFactory(
                smallOverlap, TargetAddressValidator.disabled(), sleeps::add),
            smallOverlap,
            fileProcessingService,
            attachmentIndexer(),
            indexingJobService,
            documentRepository,
            eventRepository,
            storageQuotaService,
            cleanupService,
            syncStateRepository,
            vectorChunkStore,
            Clock.fixed(NOW, ZoneOffset.UTC));
    executor.execute(UUID.randomUUID(), library, IndexingRunMode.INCREMENTAL);

    assertThat(withDefaultOverlap - searchWindowMinutes()).isBetween(8L, 10L);
  }

  @ParameterizedTest
  @MethodSource("editions")
  void aPageMovedBetweenSelectedSpacesLeavesNoStaleCopyBehind(ConfluenceEdition edition)
      throws Exception {
    start(edition, null, "ENG", "HR");
    completedFullSync(NOW.minus(Duration.ofHours(2)));
    String oldPath = pagePath(edition, "HR", "200");
    Document oldDocument =
        new Document("Onboarding", oldPath, "text/html", 10L, DocumentSourceType.CONFLUENCE);
    oldDocument.setStatus(DocumentStatus.INDEXED);
    oldDocument.setLastModifiedRemote("1");
    when(documentRepository.findByLibraryIdAndFilePath(library.getId(), oldPath))
        .thenReturn(Optional.of(oldDocument));
    server.movePage("200", "ENG", NOW.minus(Duration.ofMinutes(30)));

    executor.execute(jobId, library, IndexingRunMode.INCREMENTAL);

    String newPath = pagePath(edition, "ENG", "200");
    verify(fileProcessingService)
        .processConfluencePage(any(), eq("Onboarding"), eq(newPath), eq("2"), any(), eq(library));
    if (edition == ConfluenceEdition.CLOUD) {
      // the identity URL carries the space key: the old document is a positive finding to remove
      verify(documentRepository).delete(oldDocument);
      verify(vectorChunkStore).deleteByDocumentId(oldDocument.getId());
      verify(eventRepository)
          .save(
              argThat(
                  event(
                      IndexingEventCategory.REMOVED,
                      ConfluenceIndexingExecutor.MOVED_MESSAGE,
                      oldPath)));
    } else {
      // Data Center's URL has no space key: the same document, updated in place
      assertThat(newPath).isEqualTo(oldPath);
      verify(documentRepository, never()).delete(any(Document.class));
    }
    verify(cleanupService, never()).cleanupVanished(any(), any(), any(), any(), any(), any());
  }

  @ParameterizedTest
  @MethodSource("editions")
  void aFailedPageKeepsTheAnchorSoTheWindowIsSearchedAgain(ConfluenceEdition edition)
      throws Exception {
    start(edition, null, "ENG");
    Instant anchor = NOW.minus(Duration.ofHours(2));
    ConfluenceSyncState state = completedFullSync(anchor);
    server.updatePage("101", "<p>geändert</p>", NOW.minus(Duration.ofMinutes(20)));
    when(fileProcessingService.processConfluencePage(
            any(), eq("Kapitel 1"), any(), any(), any(), any()))
        .thenReturn(FileProcessingResult.FAILED);

    executor.execute(jobId, library, IndexingRunMode.INCREMENTAL);

    verify(indexingJobService).completeJob(jobId, 0, 1, 0, 0);
    assertThat(state.getIncrementalAnchor()).as("unchanged after a failure").isEqualTo(anchor);
    verify(syncStateRepository, never()).save(state);
  }

  @ParameterizedTest
  @MethodSource("editions")
  void anIncrementalRunWithoutACompletedFullSyncFailsWithAClearMessage(ConfluenceEdition edition)
      throws Exception {
    start(edition, null, "ENG");

    executor.execute(jobId, library, IndexingRunMode.INCREMENTAL);

    verify(indexingJobService).failJob(eq(jobId), contains("abgeschlossenen Vollabgleich"));
    verify(fileProcessingService, never())
        .processConfluencePage(any(), any(), any(), any(), any(), any());
    assertThat(server.requests()).noneMatch(r -> r.contains("search"));
  }

  @ParameterizedTest
  @MethodSource("editions")
  void theDefaultRunModeFollowsTheSyncState(ConfluenceEdition edition) throws Exception {
    start(edition, null, "ENG");
    assertThat(executor.defaultRunMode(library)).as("no state yet").isEqualTo(IndexingRunMode.FULL);

    ConfluenceSyncState state = completedFullSync(NOW.minus(Duration.ofDays(2)));
    assertThat(executor.defaultRunMode(library))
        .as("recent full sync")
        .isEqualTo(IndexingRunMode.INCREMENTAL);

    state.beginFullSync(UUID.randomUUID());
    assertThat(executor.defaultRunMode(library)).as("interrupted").isEqualTo(IndexingRunMode.FULL);

    ConfluenceSyncState old = new ConfluenceSyncState(library.getId());
    old.beginFullSync(UUID.randomUUID());
    old.completeFullSync(NOW.minus(Duration.ofDays(8)), NOW.minus(Duration.ofDays(8)));
    when(syncStateRepository.findByLibraryId(library.getId())).thenReturn(Optional.of(old));
    assertThat(executor.defaultRunMode(library))
        .as("older than the weekly interval")
        .isEqualTo(IndexingRunMode.FULL);

    // the library's own rhythm takes precedence over the instance-wide default - the same
    // 8-day-old state reads as recent under a 30-day rhythm ...
    library.updateConfluenceFullSyncIntervalDays(30);
    assertThat(executor.defaultRunMode(library))
        .as("8 days old, own rhythm 30 days")
        .isEqualTo(IndexingRunMode.INCREMENTAL);
    // ... and a 2-day-old state as due under a 1-day rhythm
    completedFullSync(NOW.minus(Duration.ofDays(2)));
    library.updateConfluenceFullSyncIntervalDays(1);
    assertThat(executor.defaultRunMode(library))
        .as("2 days old, own rhythm 1 day")
        .isEqualTo(IndexingRunMode.FULL);
  }

  @ParameterizedTest
  @MethodSource("editions")
  void aWebhookRunFetchesExactlyTheNamedPagesAndNeverListsOrReconciles(ConfluenceEdition edition)
      throws Exception {
    // the notification named Kapitel 1 (changed), Abschnitt 1.1 (known and unchanged) and
    // a page in a space the library does not select; nothing else is touched.
    start(edition, null, "ENG");
    ConfluenceSyncState state = completedFullSync(NOW.minus(Duration.ofHours(2)));
    server.updatePage("101", "<p>Das erste Kapitel, per Webhook.</p>", NOW);
    String abschnitt = pagePath(edition, "ENG", "102");
    Document indexed =
        new Document("Abschnitt 1.1", abschnitt, "text/html", 10L, DocumentSourceType.CONFLUENCE);
    indexed.setStatus(DocumentStatus.INDEXED);
    indexed.setLastModifiedRemote("1");
    indexed.applySourceContext(new SourceDocumentContext("ENG", "Handbuch / Kapitel 1"));
    when(documentRepository.findByLibraryIdAndFilePath(library.getId(), abschnitt))
        .thenReturn(Optional.of(indexed));

    executor.refreshPages(jobId, library, Set.of("101", "102", "200"));

    verify(fileProcessingService)
        .processConfluencePage(
            contains("per Webhook"),
            eq("Kapitel 1"),
            eq(pagePath(edition, "ENG", "101")),
            eq("2"),
            eq(new SourceDocumentContext("ENG", "Handbuch")),
            eq(library));
    // unchanged: no body processing, but the attachments are checked
    verify(fileProcessingService, never())
        .processConfluencePage(any(), eq("Abschnitt 1.1"), any(), any(), any(), any());
    verify(fileProcessingService)
        .processUrlFile(
            any(),
            eq("notizen.txt"),
            any(),
            any(),
            anyLong(),
            eq(library),
            eq(DocumentSourceType.CONFLUENCE),
            eq(abschnitt),
            eq(indexed.getId()),
            withContext(new SourceDocumentContext("ENG", "Handbuch / Kapitel 1 / Abschnitt 1.1")));
    verify(eventRepository)
        .save(argThat(event(IndexingEventCategory.REJECTED, "nicht ausgewählten Space", "200")));
    assertThat(server.requests())
        .noneMatch(r -> r.contains("search"))
        .noneMatch(r -> r.matches(".*/(content|pages)/100(\\?.*)?$"));
    verify(cleanupService, never()).cleanupVanished(any(), any(), any(), any(), any(), any());
    verify(indexingJobService).completeJob(jobId, 1, 0, 2, 2);
    // a webhook run fetches named pages only and never judges the listing
    verify(indexingJobService, never()).recordListingAssessment(any(), anyBoolean(), any());
    // the heartbeat moves with every page, so the stale-run sweep never mistakes a long batch
    verify(indexingJobService, atLeast(3))
        .updateProgress(eq(jobId), anyInt(), anyInt(), anyInt(), anyInt());
    // the anchor is untouched: the next incremental run re-reads these pages once more
    assertThat(state.getIncrementalAnchor()).isEqualTo(NOW.minus(Duration.ofHours(2)));
    verify(syncStateRepository, never()).save(any());
  }

  @ParameterizedTest
  @MethodSource("editions")
  void aWebhookRunRemovesOnlyWhatTheInstanceReportsAsTrashed(ConfluenceEdition edition)
      throws Exception {
    // ADR-0023, Entscheidung 4: the notification is not a finding - the fetch is. A trashed page
    // goes with its attachments, a 404 leaves the Bestand alone.
    start(edition, null, "ENG");
    completedFullSync(NOW.minus(Duration.ofHours(2)));
    String kapitel = pagePath(edition, "ENG", "101");
    String abschnitt = pagePath(edition, "ENG", "102");
    Document kapitelDoc =
        new Document("Kapitel 1", kapitel, "text/html", 10L, DocumentSourceType.CONFLUENCE);
    Document anhang = confluenceAttachment("notizen.eml", kapitel + "#900", kapitelDoc);
    Document nested =
        confluenceAttachment("anlage.pdf", anhang.getFilePath() + "/0/anlage.pdf", anhang);
    when(documentRepository.findByLibraryIdAndFilePath(library.getId(), kapitel))
        .thenReturn(Optional.of(kapitelDoc));
    when(documentRepository.findByParentDocumentId(kapitelDoc.getId())).thenReturn(List.of(anhang));
    when(documentRepository.findByParentDocumentId(anhang.getId())).thenReturn(List.of(nested));
    server.trashPage("101");
    server.hideFromFetch("102");

    executor.refreshPages(jobId, library, Set.of("101", "102"));

    // fk_documents_parent: the deepest attachment goes first, the page last
    org.mockito.InOrder deletes = org.mockito.Mockito.inOrder(documentRepository);
    deletes.verify(documentRepository).delete(nested);
    deletes.verify(documentRepository).delete(anhang);
    deletes.verify(documentRepository).delete(kapitelDoc);
    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.REMOVED,
                    ConfluenceIndexingExecutor.TRASHED_MESSAGE,
                    kapitel)));
    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.REJECTED,
                    ConfluenceIndexingExecutor.UNREADABLE_PAGE_SUFFIX,
                    "102")));
    verify(documentRepository, never()).delete(argThat(d -> abschnitt.equals(d.getFilePath())));
    verify(indexingJobService).completeJob(jobId, 0, 0, 2, 0);
  }

  @ParameterizedTest
  @MethodSource("editions")
  void aWebhookRunFailsVisiblyWhenTheCredentialsAreRejected(ConfluenceEdition edition)
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

    executor.refreshPages(jobId, library, Set.of("101"));

    verify(indexingJobService).failJob(eq(jobId), any());
    verify(fileProcessingService, never())
        .processConfluencePage(any(), any(), any(), any(), any(), any());
  }

  @ParameterizedTest
  @MethodSource("editions")
  void anExhaustedBudgetEndsTheFullSyncOrderlyAndTheNextRunContinues(ConfluenceEdition edition)
      throws Exception {
    // verify (1) + list ENG (2 pages of 2 = 2) + 3 pages x (fetch + attachments) ... the
    // budget of 6 runs out inside ENG; the run ends COMPLETED and incomplete, not FAILED.
    requestBudget = 6;
    start(edition, null, "ENG", "HR");

    executor.execute(jobId, library, IndexingRunMode.FULL);

    verify(indexingJobService, never()).failJob(any(), any());
    verify(indexingJobService).completeJob(eq(jobId), anyInt(), eq(0), anyInt(), anyInt());
    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.BUDGET_EXHAUSTED,
                    "Anfragebudget von 6 Anfragen erschöpft",
                    null)));
    ArgumentCaptor<IndexingRunCost> metrics = ArgumentCaptor.forClass(IndexingRunCost.class);
    verify(indexingJobService).recordRunMetrics(eq(jobId), metrics.capture());
    assertThat(metrics.getValue().incomplete()).isTrue();
    assertThat(metrics.getValue().requestsSent()).isEqualTo(6);
    // a budget-truncated run has not seen every space and must not overwrite the verdict
    verify(indexingJobService, never()).recordListingAssessment(any(), anyBoolean(), any());
    // no reconciliation on an incomplete listing, and the full sync stays open
    verify(cleanupService, never()).cleanupVanished(any(), any(), any(), any(), any(), any());
    ArgumentCaptor<ConfluenceSyncState> state = ArgumentCaptor.forClass(ConfluenceSyncState.class);
    verify(syncStateRepository, atLeast(1)).save(state.capture());
    assertThat(state.getValue().isFullSyncInterrupted()).isTrue();
    assertThat(state.getValue().getIncrementalAnchor()).isNull();

    // the next run, unbounded, continues from the saved state and completes
    when(syncStateRepository.findByLibraryId(library.getId()))
        .thenReturn(Optional.of(state.getValue()));
    requestBudget = 0;
    sleeps.clear();
    ConfluenceProperties unbounded =
        new ConfluenceProperties(
            2, null, null, 3, Duration.ofSeconds(2), 0, 0, null, 0, FULL_SYNC_INTERVAL, OVERLAP, 0);
    executor =
        new ConfluenceIndexingExecutor(
            new ConfluenceClientFactory(unbounded, TargetAddressValidator.disabled(), sleeps::add),
            unbounded,
            fileProcessingService,
            attachmentIndexer(),
            indexingJobService,
            documentRepository,
            eventRepository,
            storageQuotaService,
            cleanupService,
            syncStateRepository,
            vectorChunkStore,
            Clock.fixed(NOW, ZoneOffset.UTC));
    UUID second = UUID.randomUUID();
    executor.execute(second, library, IndexingRunMode.FULL);

    verify(cleanupService).cleanupVanished(any(), any(), any(), any(), any(), any());
    verify(syncStateRepository, atLeast(2)).save(state.capture());
    assertThat(state.getValue().isFullSyncInterrupted()).isFalse();
    assertThat(state.getValue().getIncrementalAnchor()).isEqualTo(NOW);
  }

  @ParameterizedTest
  @MethodSource("editions")
  void anExhaustedBudgetLeavesTheIncrementalAnchorWhereItWas(ConfluenceEdition edition)
      throws Exception {
    requestBudget = 3;
    start(edition, null, "ENG", "HR");
    Instant anchor = NOW.minus(Duration.ofHours(2));
    ConfluenceSyncState state = completedFullSync(anchor);
    server.updatePage("101", "<p>neu</p>", NOW.minus(Duration.ofHours(1)));
    server.updatePage("200", "<p>neu</p>", NOW.minus(Duration.ofMinutes(30)));

    executor.execute(jobId, library, IndexingRunMode.INCREMENTAL);

    verify(indexingJobService, never()).failJob(any(), any());
    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.BUDGET_EXHAUSTED,
                    "dasselbe Änderungsfenster erneut",
                    null)));
    assertThat(state.getIncrementalAnchor()).isEqualTo(anchor);
    verify(syncStateRepository, never()).save(state);
  }

  @ParameterizedTest
  @MethodSource("editions")
  void aRunRecordsItsCostAndItsAttachmentShare(ConfluenceEdition edition) throws Exception {
    start(edition, null, "ENG");
    server.throttleNext(1, "1");

    executor.execute(jobId, library, IndexingRunMode.FULL);

    ArgumentCaptor<IndexingRunCost> metrics = ArgumentCaptor.forClass(IndexingRunCost.class);
    verify(indexingJobService).recordRunMetrics(eq(jobId), metrics.capture());
    IndexingRunCost recorded = metrics.getValue();
    assertThat(recorded.incomplete()).isFalse();
    assertThat(recorded.requestsSent()).isEqualTo(server.requests().size());
    assertThat(recorded.throttleCount()).isEqualTo(1);
    assertThat(recorded.throttleWaitMillis()).isEqualTo(1000L);
    assertThat(recorded.attachmentsProcessed()).isEqualTo(1);
    assertThat(recorded.attachmentsSkipped()).isZero();
    assertThat(recorded.attachmentsFailed()).isZero();
  }

  @ParameterizedTest
  @MethodSource("editions")
  void aResumedFullSyncSpendsNothingOnPagesAlreadyStoredAndConverges(ConfluenceEdition edition)
      throws Exception {
    // the first run stores Handbuch (100) and Kapitel 1 (101) and runs out of budget; the
    // second run, resumed with the same budget, must not spend a call on those two pages - not even
    // for their attachment lists - and therefore reaches the rest and completes.
    requestBudget = 7;
    start(edition, null, "ENG");
    List<Document> stored = new ArrayList<>();
    when(fileProcessingService.processConfluencePage(any(), any(), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              Document doc =
                  new Document(
                      inv.getArgument(1),
                      inv.getArgument(2),
                      "text/html",
                      10L,
                      DocumentSourceType.CONFLUENCE);
              doc.setStatus(DocumentStatus.INDEXED);
              doc.setLastModifiedRemote(inv.getArgument(3));
              doc.applySourceContext(inv.getArgument(4));
              stored.add(doc);
              return FileProcessingResult.PROCESSED;
            });
    when(documentRepository.findByLibraryIdAndFilePath(any(), anyString()))
        .thenAnswer(
            inv ->
                stored.stream()
                    .filter(d -> d.getFilePath().equals(inv.getArgument(1)))
                    .findFirst());
    ArgumentCaptor<ConfluenceSyncState> state = ArgumentCaptor.forClass(ConfluenceSyncState.class);

    executor.execute(jobId, library, IndexingRunMode.FULL);

    verify(eventRepository)
        .save(argThat(event(IndexingEventCategory.BUDGET_EXHAUSTED, "Anfragebudget", null)));
    verify(eventRepository, never())
        .save(argThat(event(IndexingEventCategory.ERROR, "reicht für diese Bibliothek", null)));
    assertThat(stored).as("progress before the budget ran out").isNotEmpty();
    int firstRunPages = stored.size();
    verify(syncStateRepository, atLeast(1)).save(state.capture());
    when(syncStateRepository.findByLibraryId(library.getId()))
        .thenReturn(Optional.of(state.getValue()));
    server.requests().clear();

    UUID second = UUID.randomUUID();
    executor.execute(second, library, IndexingRunMode.FULL);

    for (Document done : stored.subList(0, firstRunPages)) {
      String id = done.getFilePath().replaceAll(".*[=/](\\d+)$", "$1");
      assertThat(server.requests())
          .as("no call for an already stored page")
          .noneMatch(r -> r.matches(".*/(content|pages)/" + id + "(/.*|\\?.*)?$"));
    }
    assertThat(stored).as("the remaining pages were taken in").hasSizeGreaterThan(firstRunPages);
  }

  @ParameterizedTest
  @MethodSource("editions")
  void aBudgetTooSmallForAnyProgressIsReportedAsAnError(ConfluenceEdition edition)
      throws Exception {
    // verify (1) + listing ENG page 1 (2) + page 2 (3) - no page body fits
    requestBudget = 3;
    start(edition, null, "ENG");

    executor.execute(jobId, library, IndexingRunMode.FULL);

    verify(indexingJobService, never()).failJob(any(), any());
    verify(eventRepository)
        .save(
            argThat(
                event(IndexingEventCategory.ERROR, "reicht für diese Bibliothek nicht aus", null)));
    verify(fileProcessingService, never())
        .processConfluencePage(any(), any(), any(), any(), any(), any());
  }

  @ParameterizedTest
  @MethodSource("editions")
  void aBudgetSpentOnTheChangeSearchEndsTheIncrementalRunOrderly(ConfluenceEdition edition)
      throws Exception {
    // the credential check takes the only call; the search itself is refused
    requestBudget = 1;
    start(edition, null, "ENG");
    ConfluenceSyncState state = completedFullSync(NOW.minus(Duration.ofHours(2)));

    executor.execute(jobId, library, IndexingRunMode.INCREMENTAL);

    verify(indexingJobService, never()).failJob(any(), any());
    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.BUDGET_EXHAUSTED,
                    "dasselbe Änderungsfenster erneut",
                    null)));
    ArgumentCaptor<IndexingRunCost> cost = ArgumentCaptor.forClass(IndexingRunCost.class);
    verify(indexingJobService).recordRunMetrics(eq(jobId), cost.capture());
    assertThat(cost.getValue().incomplete()).isTrue();
    verify(syncStateRepository, never()).save(state);
  }

  @ParameterizedTest
  @MethodSource("editions")
  void aWebhookRunLeavesTheRemainingPagesToTheNextRunWhenTheBudgetRunsOut(ConfluenceEdition edition)
      throws Exception {
    // the credential check and the first page fit, the first page's attachment list or the
    // second page is refused - either way exactly one page is stored
    requestBudget = 3;
    start(edition, null, "ENG");

    executor.refreshPages(jobId, library, Set.of("100", "101"));

    verify(indexingJobService, never()).failJob(any(), any());
    verify(fileProcessingService, times(1))
        .processConfluencePage(any(), any(), any(), any(), any(), any());
    verify(eventRepository)
        .save(
            argThat(
                event(IndexingEventCategory.BUDGET_EXHAUSTED, "übrigen gemeldeten Seiten", null)));
    ArgumentCaptor<IndexingRunCost> cost = ArgumentCaptor.forClass(IndexingRunCost.class);
    verify(indexingJobService).recordRunMetrics(eq(jobId), cost.capture());
    assertThat(cost.getValue().incomplete()).isTrue();
  }

  private static ArgumentMatcher<IndexingRunEvent> event(
      IndexingEventCategory category, String messagePart, String reference) {
    return event ->
        event.getCategory() == category
            && event.getMessage().contains(messagePart)
            && (reference == null || reference.equals(event.getReference()));
  }
}
