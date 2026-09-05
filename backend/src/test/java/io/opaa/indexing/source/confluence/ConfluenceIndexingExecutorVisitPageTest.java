package io.opaa.indexing.source.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ConfluenceEdition;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentIngests;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.IndexingEventCategory;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.IndexingRunEvent;
import io.opaa.indexing.IndexingRunEventRecorder;
import io.opaa.indexing.IndexingRunEventRepository;
import io.opaa.indexing.IndexingRunProgress;
import io.opaa.indexing.SourceDocumentContext;
import io.opaa.indexing.VectorChunkStore;
import io.opaa.indexing.source.IndexingRun;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.attachment.AttachmentIndexer;
import io.opaa.indexing.source.confluence.ConfluenceIndexingExecutor.PageVisitPolicy;
import io.opaa.library.ConfluenceSpaceSelection;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryStorageQuotaService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentMatcher;

/**
 * The one page visit under each {@link PageVisitPolicy}, against a mocked {@link ConfluenceClient}:
 * what a trashed, forbidden, vanished, unreachable, moved, deselected, unchanged or changed page
 * does to the protocol, the counters, the reconciliation set and the index - and where the three
 * Betriebsarten deliberately differ. The end-to-end behaviour of every run mode stays with {@link
 * ConfluenceIndexingExecutorTest}.
 */
class ConfluenceIndexingExecutorVisitPageTest {

  private static final String BASE = "https://wiki.example";

  private ConfluenceClient client;
  private DocumentRepository documentRepository;
  private FileProcessingService fileProcessingService;
  private IndexingRunEventRepository eventRepository;
  private VectorChunkStore vectorChunkStore;
  private KnowledgeLibrary library;
  private ConfluenceRun run;
  private ConfluenceIndexingExecutor executor;

  static Stream<PageVisitPolicy> listedPolicies() {
    return Stream.of(PageVisitPolicy.FULL_SYNC, PageVisitPolicy.INCREMENTAL);
  }

  static Stream<PageVisitPolicy> positiveFindingPolicies() {
    return Stream.of(PageVisitPolicy.INCREMENTAL, PageVisitPolicy.WEBHOOK);
  }

  @BeforeEach
  void setUp() throws Exception {
    client = mock(ConfluenceClient.class);
    when(client.pageUrl(any(), any()))
        .thenAnswer(
            inv -> BASE + "/wiki/spaces/" + inv.getArgument(0) + "/pages/" + inv.getArgument(1));
    when(client.listAttachments(anyString())).thenReturn(List.of());
    documentRepository = mock(DocumentRepository.class);
    when(documentRepository.findByLibraryIdAndFilePath(any(), anyString()))
        .thenReturn(Optional.empty());
    fileProcessingService = mock(FileProcessingService.class);
    when(fileProcessingService.ingest(any(), any())).thenReturn(FileProcessingResult.PROCESSED);
    eventRepository = mock(IndexingRunEventRepository.class);
    vectorChunkStore = mock(VectorChunkStore.class);
    IndexingJobService indexingJobService = mock(IndexingJobService.class);
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
            BASE,
            null,
            "token",
            false);
    library.configureConfluence(
        ConfluenceEdition.CLOUD,
        List.of(
            new ConfluenceSpaceSelection("ENG", null), new ConfluenceSpaceSelection("HR", null)));
    UUID jobId = UUID.randomUUID();
    IndexingRun frame =
        new IndexingRun(
            jobId,
            library,
            IndexingRunMode.FULL,
            DocumentSourceType.CONFLUENCE,
            new IndexingRunProgress(indexingJobService, jobId),
            new IndexingRunEventRecorder(eventRepository, indexingJobService, jobId),
            documentRepository,
            mock(LibraryStorageQuotaService.class));
    run = new ConfluenceRun(frame, client);
    ConfluenceProperties properties =
        new ConfluenceProperties(
            2, null, null, 3, Duration.ofSeconds(2), 0, 0, null, 0, null, null, 0);
    executor =
        new ConfluenceIndexingExecutor(
            mock(ConfluenceClientFactory.class),
            properties,
            fileProcessingService,
            mock(AttachmentIndexer.class),
            documentRepository,
            mock(ConfluenceSyncStateRepository.class),
            vectorChunkStore,
            Clock.systemUTC(),
            mock(IndexingRunTemplate.class));
  }

  // ---- fixtures ------------------------------------------------------------------------------

  private static String pagePath(String spaceKey, String id) {
    return BASE + "/wiki/spaces/" + spaceKey + "/pages/" + id;
  }

  /** What the visit knows before any call: the listing entry, or for the webhook the id alone. */
  private static ConfluencePageSummary known(
      PageVisitPolicy policy, String id, String spaceKey, String title, int version) {
    return policy == PageVisitPolicy.WEBHOOK
        ? ConfluenceIndexingExecutor.reported(id)
        : new ConfluencePageSummary(id, spaceKey, title, version, null);
  }

  private static ConfluencePage page(
      String id, String spaceKey, String title, int version, ConfluencePageStatus status) {
    return new ConfluencePage(
        id,
        spaceKey,
        title,
        version,
        status,
        List.of("Handbuch"),
        "<p>Inhalt von " + title + "</p>",
        pagePath(spaceKey, id),
        Instant.parse("2026-09-01T10:00:00Z"));
  }

  private Document indexed(String title, String path, String version) {
    Document document = new Document(title, path, "text/html", 10L, DocumentSourceType.CONFLUENCE);
    document.setStatus(DocumentStatus.INDEXED);
    document.setLastModifiedRemote(version);
    document.setLibraryId(library.getId());
    document.applySourceContext(new SourceDocumentContext("ENG", "Handbuch"));
    when(documentRepository.findByLibraryIdAndFilePath(library.getId(), path))
        .thenReturn(Optional.of(document));
    return document;
  }

  private Document attachmentOf(Document parent, String fileName) {
    Document attachment =
        new Document(
            fileName,
            parent.getFilePath() + "/" + fileName,
            "application/octet-stream",
            5L,
            DocumentSourceType.CONFLUENCE);
    attachment.setParentDocumentId(parent.getId());
    when(documentRepository.findByParentDocumentId(parent.getId())).thenReturn(List.of(attachment));
    return attachment;
  }

  private static String label(PageVisitPolicy policy, String id, String spaceKey, String title) {
    return policy == PageVisitPolicy.WEBHOOK
        ? "Seite " + id + " (per Webhook gemeldet) "
        : "Seite „" + title + "“ (Space " + spaceKey + ") ";
  }

  /** The protocol names a listed page by its URL, a webhook-reported one by its id. */
  private static String reference(PageVisitPolicy policy, String id, String spaceKey) {
    return policy == PageVisitPolicy.WEBHOOK ? id : pagePath(spaceKey, id);
  }

  private static ArgumentMatcher<IndexingRunEvent> event(
      IndexingEventCategory category, String message, String reference) {
    return event ->
        event.getCategory() == category
            && event.getMessage().equals(message)
            && reference.equals(event.getReference());
  }

  // ---- trashed -------------------------------------------------------------------------------

  @ParameterizedTest
  @EnumSource(PageVisitPolicy.class)
  void aTrashedPageGoesWithItsAttachmentsDeepestFirst(PageVisitPolicy policy) throws Exception {
    String path = pagePath("ENG", "101");
    Document page = indexed("Kapitel 1", path, "1");
    Document attachment = attachmentOf(page, "notizen.txt");
    when(client.fetchPage("101"))
        .thenReturn(Optional.of(page("101", "ENG", "Kapitel 1", 2, ConfluencePageStatus.TRASHED)));

    executor.visitPage(run, known(policy, "101", "ENG", "Kapitel 1", 2), policy);

    var deletes = inOrder(documentRepository);
    deletes.verify(documentRepository).delete(attachment);
    deletes.verify(documentRepository).delete(page);
    verify(vectorChunkStore).deleteByDocumentId(page.getId());
    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.REMOVED,
                    ConfluenceIndexingExecutor.TRASHED_MESSAGE,
                    path)));
    assertThat(run.progress.skippedCount()).isEqualTo(1);
    assertThat(run.frame.currentPaths()).as("no longer part of the bestand").doesNotContain(path);
    verify(fileProcessingService, never()).ingest(any(), any());
  }

  @Test
  void aWebhookVisitRemovesATrashedPageEvenAtAnUnchangedVersion() throws Exception {
    // trashing does not bump the version: the webhook, which fetches before it compares, must
    // still act on the trash status instead of treating the page as unchanged
    Document page = indexed("Kapitel 1", pagePath("ENG", "101"), "1");
    when(client.fetchPage("101"))
        .thenReturn(Optional.of(page("101", "ENG", "Kapitel 1", 1, ConfluencePageStatus.TRASHED)));

    executor.visitPage(run, ConfluenceIndexingExecutor.reported("101"), PageVisitPolicy.WEBHOOK);

    verify(documentRepository).delete(page);
    verify(client, never()).listAttachments(anyString());
  }

  // ---- 403 / 404 / unreachable ---------------------------------------------------------------

  @ParameterizedTest
  @EnumSource(PageVisitPolicy.class)
  void aForbiddenPageIsSkippedVisiblyAndStaysIndexed(PageVisitPolicy policy) throws Exception {
    String path = pagePath("ENG", "101");
    indexed("Kapitel 1", path, "1");
    when(client.fetchPage("101")).thenThrow(new ConfluenceAccessException.Forbidden("403"));

    executor.visitPage(run, known(policy, "101", "ENG", "Kapitel 1", 2), policy);

    assertUnreadable(policy, path);
  }

  @ParameterizedTest
  @EnumSource(PageVisitPolicy.class)
  void aVanishedPageIsSkippedVisiblyAndStaysIndexed(PageVisitPolicy policy) throws Exception {
    String path = pagePath("ENG", "101");
    indexed("Kapitel 1", path, "1");
    when(client.fetchPage("101")).thenReturn(Optional.empty());

    executor.visitPage(run, known(policy, "101", "ENG", "Kapitel 1", 2), policy);

    assertUnreadable(policy, path);
  }

  /** ADR-0023, Entscheidung 4: neither a 403 nor a 404 is a deletion finding. */
  private void assertUnreadable(PageVisitPolicy policy, String path) throws Exception {
    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.REJECTED,
                    label(policy, "101", "ENG", "Kapitel 1")
                        + ConfluenceIndexingExecutor.UNREADABLE_PAGE_SUFFIX,
                    reference(policy, "101", "ENG"))));
    assertThat(run.progress.skippedCount()).isEqualTo(1);
    assertThat(run.progress.failedCount()).isZero();
    verify(documentRepository, never()).delete(any(Document.class));
    verify(fileProcessingService, never()).ingest(any(), any());
    if (policy.reconciles()) {
      assertThat(run.frame.currentPaths()).as("present, not reprocessed").contains(path);
      assertThat(run.frame.reprocessedPaths()).doesNotContain(path);
    }
  }

  @ParameterizedTest
  @EnumSource(PageVisitPolicy.class)
  void anUnreachablePageCountsAsFailed(PageVisitPolicy policy) throws Exception {
    when(client.fetchPage("101"))
        .thenThrow(new ConfluenceAccessException("Confluence antwortet nicht (HTTP 502)"));

    executor.visitPage(run, known(policy, "101", "ENG", "Kapitel 1", 2), policy);

    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.UNREACHABLE,
                    label(policy, "101", "ENG", "Kapitel 1")
                        + "Confluence antwortet nicht (HTTP 502)",
                    reference(policy, "101", "ENG"))));
    assertThat(run.progress.failedCount()).isEqualTo(1);
    assertThat(run.progress.skippedCount()).isZero();
  }

  // ---- moved / deselected --------------------------------------------------------------------

  @ParameterizedTest
  @MethodSource("positiveFindingPolicies")
  void aPageFoundUnderANewSpaceUrlRemovesTheOldCopyAsAPositiveFinding(PageVisitPolicy policy)
      throws Exception {
    String oldPath = pagePath("HR", "200");
    Document old = indexed("Onboarding", oldPath, "1");
    when(client.fetchPage("200"))
        .thenReturn(Optional.of(page("200", "ENG", "Onboarding", 2, ConfluencePageStatus.CURRENT)));

    executor.visitPage(run, known(policy, "200", "ENG", "Onboarding", 2), policy);

    verify(documentRepository).delete(old);
    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.REMOVED,
                    ConfluenceIndexingExecutor.MOVED_MESSAGE,
                    oldPath)));
    verify(fileProcessingService)
        .ingest(
            DocumentIngests.that().text().at(pagePath("ENG", "200")).marked("2").match(), any());
  }

  @Test
  void aFullSyncLeavesTheOldCopyOfAMovedPageToTheReconciliation() throws Exception {
    // absence is evidence for a complete listing: the old URL is simply not met again
    Document old = indexed("Onboarding", pagePath("HR", "200"), "1");
    when(client.fetchPage("200"))
        .thenReturn(Optional.of(page("200", "ENG", "Onboarding", 2, ConfluencePageStatus.CURRENT)));

    executor.visitPage(
        run,
        known(PageVisitPolicy.FULL_SYNC, "200", "ENG", "Onboarding", 2),
        PageVisitPolicy.FULL_SYNC);

    verify(documentRepository, never()).delete(old);
    assertThat(run.frame.currentPaths())
        .contains(pagePath("ENG", "200"))
        .doesNotContain(old.getFilePath());
  }

  @ParameterizedTest
  @MethodSource("positiveFindingPolicies")
  void aPageOutsideTheSelectionIsLeftAloneUntilTheNextFullSync(PageVisitPolicy policy)
      throws Exception {
    Document stale = indexed("Streng geheim", pagePath("SEC", "300"), "1");
    when(client.fetchPage("300"))
        .thenReturn(
            Optional.of(page("300", "SEC", "Streng geheim", 2, ConfluencePageStatus.CURRENT)));

    executor.visitPage(run, known(policy, "300", "SEC", "Streng geheim", 2), policy);

    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.REJECTED,
                    "Seite „Streng geheim“ (Space SEC) "
                        + ConfluenceIndexingExecutor.NOT_SELECTED_SUFFIX,
                    "300")));
    assertThat(run.progress.skippedCount()).isEqualTo(1);
    verify(documentRepository, never()).delete(stale);
    verify(fileProcessingService, never()).ingest(any(), any());
    verify(client, never()).listAttachments(anyString());
    if (policy == PageVisitPolicy.INCREMENTAL) {
      // the search already said where the page is - no call is spent on it
      verify(client, never()).fetchPage(anyString());
    }
  }

  // ---- budget --------------------------------------------------------------------------------

  @ParameterizedTest
  @EnumSource(PageVisitPolicy.class)
  void anExhaustedBudgetEndsTheVisitWithoutANote(PageVisitPolicy policy) throws Exception {
    when(client.fetchPage("101")).thenThrow(new ConfluenceAccessException.BudgetExhausted(6));

    assertThatThrownBy(
            () -> executor.visitPage(run, known(policy, "101", "ENG", "Kapitel 1", 2), policy))
        .isInstanceOf(ConfluenceAccessException.BudgetExhausted.class);

    verify(eventRepository, never()).save(any());
    assertThat(run.progress.skippedCount()).isZero();
    assertThat(run.progress.failedCount()).isZero();
  }

  // ---- unchanged / changed -------------------------------------------------------------------

  @ParameterizedTest
  @MethodSource("listedPolicies")
  void anUnchangedListedPageCostsNoFetchButListsItsAttachments(PageVisitPolicy policy)
      throws Exception {
    String path = pagePath("ENG", "102");
    indexed("Abschnitt 1.1", path, "1");

    executor.visitPage(run, known(policy, "102", "ENG", "Abschnitt 1.1", 1), policy);

    verify(client, never()).fetchPage(anyString());
    verify(client).listAttachments("102");
    verify(fileProcessingService, never()).ingest(any(), any());
    assertThat(run.progress.skippedCount()).isEqualTo(1);
    if (policy.reconciles()) {
      assertThat(run.frame.reprocessedPaths())
          .as("its attachment set was enumerated")
          .contains(path);
    }
  }

  @Test
  void anUnchangedReportedPageStillHasItsAttachmentsListed() throws Exception {
    indexed("Abschnitt 1.1", pagePath("ENG", "102"), "1");
    when(client.fetchPage("102"))
        .thenReturn(
            Optional.of(page("102", "ENG", "Abschnitt 1.1", 1, ConfluencePageStatus.CURRENT)));

    executor.visitPage(run, ConfluenceIndexingExecutor.reported("102"), PageVisitPolicy.WEBHOOK);

    verify(client).listAttachments("102");
    verify(fileProcessingService, never()).ingest(any(), any());
    assertThat(run.progress.skippedCount()).isEqualTo(1);
  }

  @Test
  void aResumedFullSyncSpendsNothingOnAnUnchangedPage() throws Exception {
    indexed("Abschnitt 1.1", pagePath("ENG", "102"), "1");
    run.resumed = true;

    executor.visitPage(
        run,
        known(PageVisitPolicy.FULL_SYNC, "102", "ENG", "Abschnitt 1.1", 1),
        PageVisitPolicy.FULL_SYNC);

    verify(client, never()).fetchPage(anyString());
    verify(client, never()).listAttachments(anyString());
    assertThat(run.progress.skippedCount()).isEqualTo(1);
    assertThat(run.frame.currentPaths()).contains(pagePath("ENG", "102"));
  }

  @ParameterizedTest
  @EnumSource(PageVisitPolicy.class)
  void aChangedPageIsStoredAtTheFetchedVersionWithItsPlace(PageVisitPolicy policy)
      throws Exception {
    indexed("Kapitel 1", pagePath("ENG", "101"), "1");
    when(client.fetchPage("101"))
        .thenReturn(Optional.of(page("101", "ENG", "Kapitel 1", 3, ConfluencePageStatus.CURRENT)));

    executor.visitPage(run, known(policy, "101", "ENG", "Kapitel 1", 2), policy);

    verify(fileProcessingService)
        .ingest(
            DocumentIngests.that()
                .text()
                .textContaining("Inhalt von Kapitel 1")
                .titled("Kapitel 1")
                .at(pagePath("ENG", "101"))
                .marked("3")
                .withContext(new SourceDocumentContext("ENG", "Handbuch"))
                .in(library)
                .match(),
            any());
    verify(client).listAttachments("101");
    assertThat(run.progress.processedCount()).isEqualTo(1);
  }
}
