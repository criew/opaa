package io.opaa.indexing.source.confluence;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentIngest;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.IndexingEventCategory;
import io.opaa.indexing.IndexingRunEventRecorder;
import io.opaa.indexing.SourceDocumentContext;
import io.opaa.indexing.VectorChunkStore;
import io.opaa.indexing.pipeline.DocumentProperties;
import io.opaa.indexing.pipeline.confluence.ConfluenceDocumentPipeline;
import io.opaa.indexing.source.IndexingRun;
import io.opaa.indexing.source.IndexingRunFailedException;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.IndexingSourceType;
import io.opaa.indexing.source.ListingOutcome;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.indexing.source.VanishedDocumentPolicy;
import io.opaa.indexing.source.attachment.AttachmentIndexer;
import io.opaa.library.ConfluenceSpaceSelection;
import io.opaa.library.KnowledgeLibrary;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;

/**
 * Executes indexing runs for {@link IndexingSourceType#CONFLUENCE} (ADR-0023). A full sync lists
 * every selected space completely (identifiers, titles and versions, no body), visits each page
 * ({@link #visitPage}), and reports a complete listing so the run frame reconciles. An incremental
 * run asks CQL for what changed since the anchor, a webhook run fetches exactly the reported pages;
 * neither ever reconciles. {@link #defaultRunMode} picks between full and incremental.
 *
 * <p>What may delete is narrow (Entscheidung 4): credentials are verified before the first listing,
 * an unlistable space removes nothing, an unreadable page stays indexed, and only {@code trashed}
 * or a page found under a new space's URL removes a page outside the reconciliation. An interrupted
 * full sync resumes from {@link ConfluenceSyncState}, unfinished spaces first.
 */
public class ConfluenceIndexingExecutor implements SourceIndexingExecutor {

  private static final Logger log = LoggerFactory.getLogger(ConfluenceIndexingExecutor.class);

  static final String TRASHED_MESSAGE = "In Confluence im Papierkorb, entfernt";
  static final String MOVED_MESSAGE =
      "In Confluence in einen anderen Space verschoben, alter Stand entfernt";

  /**
   * Suffixes of the skip notes; the notes themselves name space and title - what a reader of the
   * protocol needs to know what the library does not contain, in the consequence's words, not the
   * mechanism's.
   */
  static final String UNREADABLE_PAGE_SUFFIX =
      "ist für das hinterlegte Dienstkonto nicht lesbar oder nicht mehr vorhanden, übersprungen;"
          + " der bereits indizierte Stand bleibt erhalten";

  static final String UNREADABLE_SPACE_SUFFIX =
      "ist für das hinterlegte Dienstkonto nicht lesbar; sein Bestand bleibt bis zur nächsten"
          + " vollständigen Auflistung unverändert";

  static final String NOT_SELECTED_SUFFIX =
      "liegt in einem nicht ausgewählten Space; der bisherige Stand bleibt bis zum nächsten"
          + " Vollabgleich";

  private final ConfluenceClientFactory clientFactory;
  private final ConfluenceProperties properties;
  private final FileProcessingService fileProcessingService;
  private final DocumentRepository documentRepository;
  private final ConfluenceSyncStateRepository syncStateRepository;
  private final VectorChunkStore vectorChunkStore;
  private final Clock clock;
  private final IndexingRunTemplate runTemplate;
  private final ConfluenceAttachmentIndexing attachments;

  public ConfluenceIndexingExecutor(
      ConfluenceClientFactory clientFactory,
      ConfluenceProperties properties,
      FileProcessingService fileProcessingService,
      AttachmentIndexer attachmentIndexer,
      DocumentRepository documentRepository,
      ConfluenceSyncStateRepository syncStateRepository,
      VectorChunkStore vectorChunkStore,
      Clock clock,
      IndexingRunTemplate runTemplate) {
    this.clientFactory = clientFactory;
    this.properties = properties;
    this.fileProcessingService = fileProcessingService;
    this.documentRepository = documentRepository;
    this.syncStateRepository = syncStateRepository;
    this.vectorChunkStore = vectorChunkStore;
    this.clock = clock;
    this.runTemplate = runTemplate;
    this.attachments = new ConfluenceAttachmentIndexing(attachmentIndexer, documentRepository);
  }

  @Override
  public IndexingSourceType sourceType() {
    return IndexingSourceType.CONFLUENCE;
  }

  @Override
  public Map<IndexingRunMode, VanishedDocumentPolicy> runModes() {
    // ADR-0023, Entscheidung 4: the full sync is "vollständig auflistend", the incremental run
    // "ergänzend" - it never removes anything for being absent from its change window.
    return Map.of(
        IndexingRunMode.FULL,
        VanishedDocumentPolicy.REMOVE_ON_ABSENCE,
        IndexingRunMode.INCREMENTAL,
        VanishedDocumentPolicy.KEEP_ON_ABSENCE);
  }

  /**
   * ADR-0023, Entscheidung 4: the first run, a run after the space selection changed, a run after
   * an interrupted full sync and every run once {@code fullSyncInterval} has passed are full; the
   * routine run in between is incremental. Decided from the state at trigger time, so a full run
   * that fell due while an incremental one was running is taken at the next tick, not lost.
   */
  @Override
  public IndexingRunMode defaultRunMode(KnowledgeLibrary library) {
    // the library's own rhythm, where set, takes precedence over the instance-wide one.
    Duration fullSyncInterval =
        library.getConfluenceFullSyncIntervalDays() != null
            ? Duration.ofDays(library.getConfluenceFullSyncIntervalDays())
            : properties.fullSyncInterval();
    return syncStateRepository
        .findByLibraryId(library.getId())
        .filter(state -> !state.isFullSyncDue(fullSyncInterval, clock.instant()))
        .map(state -> IndexingRunMode.INCREMENTAL)
        .orElse(IndexingRunMode.FULL);
  }

  @Override
  @Async("indexingTaskExecutor")
  public void execute(UUID jobId, KnowledgeLibrary targetLibrary, IndexingRunMode runMode) {
    Instant startedAt = clock.instant();
    runTemplate.run(
        jobId,
        targetLibrary,
        runMode,
        this,
        frame ->
            withClient(
                frame,
                run ->
                    runMode == IndexingRunMode.INCREMENTAL
                        ? incrementalSync(run, startedAt)
                        : fullSync(run, startedAt)));
  }

  /**
   * The webhook run: visits exactly {@code pageIds} under {@link PageVisitPolicy#WEBHOOK}. Never a
   * listing, never a cleanup, and the incremental anchor stays where it is.
   */
  @Async("indexingTaskExecutor")
  public void refreshPages(UUID jobId, KnowledgeLibrary targetLibrary, Set<String> pageIds) {
    runTemplate.run(
        jobId,
        targetLibrary,
        IndexingRunMode.INCREMENTAL,
        this,
        frame -> withClient(frame, run -> refreshPages(run, pageIds)));
  }

  /** A sync over one verified client. */
  @FunctionalInterface
  private interface Sync {
    ListingOutcome run(ConfluenceRun run) throws ConfluenceAccessException, InterruptedException;
  }

  /**
   * Opens the run's client and verifies the credentials before the first listing (ADR-0023,
   * Entscheidung 2). Throttling and the request cost are reported whether the sync succeeded or
   * not; an access failure ends the run with the access layer's own German message.
   */
  private ListingOutcome withClient(IndexingRun frame, Sync sync) throws InterruptedException {
    ConfluenceConnection connection;
    try {
      connection = ConfluenceLibraryConnection.of(frame.library());
    } catch (ConfluenceLibraryConnection.InvalidConfluenceConfigurationException e) {
      throw new IndexingRunFailedException(e.getMessage());
    }
    ConfluenceClient client;
    try {
      client = clientFactory.createForRun(connection);
    } catch (ConfluenceAccessException e) {
      throw accessFailure(frame, e);
    }
    try {
      client.verifyCredentials();
      return sync.run(new ConfluenceRun(frame, client));
    } catch (ConfluenceAccessException e) {
      throw accessFailure(frame, e);
    } finally {
      reportThrottling(client, frame.events());
      ConfluenceRequestMeter meter = client.meter();
      frame.recordRequestCost(
          meter.requests(), meter.throttles(), meter.throttledTime().toMillis());
    }
  }

  private static IndexingRunFailedException accessFailure(
      IndexingRun frame, ConfluenceAccessException e) {
    log.warn("Confluence run for library {} failed: {}", frame.library().getId(), e.getMessage());
    return new IndexingRunFailedException(e.getMessage(), e);
  }

  /**
   * One protocol note when the budget ran out, naming where the next run continues; the listing
   * outcome the sync returns for it.
   */
  private static ListingOutcome recordBudgetExhausted(
      ConfluenceRun run, ConfluenceAccessException.BudgetExhausted e, String continuation) {
    run.events.record(
        IndexingEventCategory.BUDGET_EXHAUSTED,
        "Anfragebudget von "
            + e.budget()
            + " Anfragen erschöpft; der Lauf endet unvollständig, "
            + continuation,
        null);
    if (run.progress.processedCount() == 0 && run.progress.attachmentsProcessed() == 0) {
      // a run that stored nothing new will not do better next time - the chain has stalled
      run.events.record(
          IndexingEventCategory.ERROR,
          "Das Anfragebudget von "
              + e.budget()
              + " Anfragen reicht für diese Bibliothek nicht aus: Der Lauf hat keine Seite neu"
              + " aufgenommen. Budget anheben oder die Space-Auswahl aufteilen.",
          null);
    }
    return ListingOutcome.truncated();
  }

  private ListingOutcome fullSync(ConfluenceRun run, Instant startedAt)
      throws ConfluenceAccessException, InterruptedException {
    UUID libraryId = run.library.getId();
    ConfluenceSyncState state =
        syncStateRepository
            .findByLibraryId(libraryId)
            .orElseGet(() -> new ConfluenceSyncState(libraryId));
    List<ConfluenceSpaceSelection> spaces = orderForResumption(run.library, state);
    run.resumed = state.isFullSyncInterrupted();
    state.beginFullSync(run.frame.jobId());
    state = syncStateRepository.save(state);

    for (ConfluenceSpaceSelection space : spaces) {
      String key = space.getSpaceKey();
      List<ConfluencePageSummary> pages;
      try {
        pages = run.client.listPages(key);
      } catch (ConfluenceAccessException.BudgetExhausted e) {
        // the state already holds every completed space - the next run starts with this one
        return recordBudgetExhausted(run, e, "der nächste Lauf setzt bei Space " + key + " fort");
      } catch (ConfluenceAccessException.Forbidden | ConfluenceAccessException.NotFound e) {
        // ADR-0023, Entscheidung 4: a revoked right is no deletion finding - the run says so and
        // leaves this space's bestand alone.
        log.warn(
            "Confluence space {} not readable for library {}: {}", key, libraryId, e.getMessage());
        run.events.record(
            IndexingEventCategory.REJECTED, "Space " + key + " " + UNREADABLE_SPACE_SUFFIX, key);
        run.listingComplete = false;
        run.unreadableSpaceKeys.add(key);
        continue;
      }
      run.total += pages.size();
      run.progress.setTotal(run.total);
      run.progress.report();
      for (ConfluencePageSummary page : pages) {
        try {
          visitPage(run, page, PageVisitPolicy.FULL_SYNC);
        } catch (ConfluenceAccessException.BudgetExhausted e) {
          // pages already stored keep their version, so the next run re-lists this space
          // cheaply (listing entries only) and fetches only what is still missing
          return recordBudgetExhausted(
              run,
              e,
              "der nächste Lauf setzt bei Space "
                  + key
                  + " fort; bereits gespeicherte Seiten kosten dabei keinen Abruf");
        }
        run.progress.report();
      }
      state.markSpaceCompleted(key);
      state = syncStateRepository.save(state);
    }

    if (!run.listingComplete) {
      log.info(
          "Confluence full sync for library {} listed incompletely - keeping the bestand, no"
              + " reconciliation",
          libraryId);
      return ListingOutcome.incomplete(List.copyOf(run.unreadableSpaceKeys));
    }
    // Without the reconciliation the full sync is not complete: the state stays open, so the next
    // run reconciles again instead of anchoring an incremental run on a stale bestand.
    ConfluenceSyncState completedState = state;
    run.frame.afterReconciliation(
        reconciled -> {
          if (reconciled) {
            completedState.completeFullSync(startedAt, clock.instant());
            syncStateRepository.save(completedState);
          } else {
            run.events.record(
                IndexingEventCategory.ERROR,
                "Abgleich des Bestands fehlgeschlagen; der nächste Lauf holt ihn nach",
                null);
          }
        });
    return ListingOutcome.complete();
  }

  /**
   * The incremental run: asks CQL for the pages in the selected spaces modified since the anchor
   * minus the overlap and visits what it names. The anchor moves only when the run failed nothing,
   * so no window is lost.
   */
  private ListingOutcome incrementalSync(ConfluenceRun run, Instant startedAt)
      throws ConfluenceAccessException, InterruptedException {
    UUID libraryId = run.library.getId();
    ConfluenceSyncState state =
        syncStateRepository
            .findByLibraryId(libraryId)
            .filter(s -> s.getIncrementalAnchor() != null && !s.isFullSyncInterrupted())
            .orElseThrow(
                () ->
                    new ConfluenceAccessException(
                        "Ein inkrementeller Abgleich braucht einen abgeschlossenen Vollabgleich;"
                            + " bitte zuerst einen Vollabgleich starten."));
    Instant since = state.getIncrementalAnchor().minus(properties.incrementalOverlap());
    String continuation = "der nächste Lauf durchsucht dasselbe Änderungsfenster erneut";
    List<ConfluencePageSummary> changed;
    try {
      changed = run.client.searchPagesModifiedSince(run.selectedKeys, since);
    } catch (ConfluenceAccessException.BudgetExhausted e) {
      return recordBudgetExhausted(run, e, continuation);
    }
    run.total = changed.size();
    run.progress.setTotal(run.total);
    run.progress.report();
    for (ConfluencePageSummary summary : changed) {
      try {
        visitPage(run, summary, PageVisitPolicy.INCREMENTAL);
      } catch (ConfluenceAccessException.BudgetExhausted e) {
        // the anchor stays, so the next run searches the same window again
        return recordBudgetExhausted(run, e, continuation);
      }
      run.progress.report();
    }
    if (run.progress.failedCount() == 0) {
      state.advanceIncrementalAnchor(startedAt);
      syncStateRepository.save(state);
    } else {
      log.info(
          "Not advancing the Confluence anchor for library {} - this run failed at least one"
              + " page, the next run searches the same window again",
          libraryId);
    }
    return ListingOutcome.partial();
  }

  private ListingOutcome refreshPages(ConfluenceRun run, Set<String> pageIds)
      throws InterruptedException, ConfluenceAccessException {
    run.progress.setTotal(pageIds.size());
    for (String pageId : pageIds.stream().sorted().toList()) {
      try {
        visitPage(run, reported(pageId), PageVisitPolicy.WEBHOOK);
      } catch (ConfluenceAccessException.BudgetExhausted e) {
        return recordBudgetExhausted(
            run, e, "die übrigen gemeldeten Seiten nimmt der nächste Lauf auf");
      }
      run.progress.report();
    }
    return ListingOutcome.partial();
  }

  /** What a webhook notification says about a page: its id, nothing else. */
  static ConfluencePageSummary reported(String pageId) {
    return new ConfluencePageSummary(pageId, null, null, 0, null);
  }

  /**
   * How a run visits a page - the switches that separate the three Betriebsarten (ADR-0023,
   * Entscheidung 4). Only the full sync lists completely, so only it puts the page into the
   * reconciliation set; the other two need a positive finding for every removal, so they remove a
   * page found under a new space's URL themselves and leave one outside the selection alone. The
   * webhook knows nothing but the id and fetches before it judges.
   */
  enum PageVisitPolicy {
    FULL_SYNC,
    INCREMENTAL,
    WEBHOOK;

    boolean reconciles() {
      return this == FULL_SYNC;
    }
  }

  /**
   * The one page visit of every run mode. The version decides before any body is fetched (ADR-0017,
   * Entscheidung 2); attachments of an unchanged page are still listed, since they do not bump its
   * version - except in a resumed full sync, where the done part must cost nothing. A trashed page
   * goes with its attachments (the instance's own finding), a page the account cannot read stays as
   * it is, a page without text keeps its attachments, and a page the run could not store stays
   * present without being reprocessed, so the reconciliation preserves its attachments.
   */
  void visitPage(ConfluenceRun run, ConfluencePageSummary summary, PageVisitPolicy policy)
      throws InterruptedException, ConfluenceAccessException.BudgetExhausted {
    ConfluencePage page = null;
    if (policy == PageVisitPolicy.WEBHOOK) {
      String id = summary.id();
      page = fetchPage(run, id, "Seite " + id + " (per Webhook gemeldet) ", id);
      if (page == null) {
        return;
      }
      summary =
          new ConfluencePageSummary(page.id(), page.spaceKey(), page.title(), page.version(), null);
    }
    // a search hit outside the selection (or without a space key, so without an identity URL)
    // costs neither a URL nor a lookup
    if (policy == PageVisitPolicy.INCREMENTAL && rejectedAsUnselected(run, summary)) {
      return;
    }
    String spaceKey = summary.spaceKey();
    String pagePath = run.client.pageUrl(spaceKey, summary.id());
    if (policy.reconciles()) {
      run.frame.markPresent(pagePath);
    }
    Optional<Document> existing =
        documentRepository.findByLibraryIdAndFilePath(run.library.getId(), pagePath);
    if (page != null && page.status() == ConfluencePageStatus.TRASHED) {
      discardTrashed(run, existing, pagePath);
      return;
    }
    // a reported page is judged once fetched: the trash goes whatever its space, the rest only
    // inside the selection
    if (policy == PageVisitPolicy.WEBHOOK && rejectedAsUnselected(run, summary)) {
      return;
    }
    if (!policy.reconciles() && existing.isEmpty()) {
      removeMovedFrom(run, summary, pagePath);
    }
    if (isUnchanged(existing, String.valueOf(summary.version()))) {
      run.progress.recordSkipped();
      if (!run.resumed) {
        SourceDocumentContext context =
            new SourceDocumentContext(spaceKey, existing.get().getSourceHierarchyPath());
        attachments.indexAttachments(
            run, summary.id(), pagePath, existing.get().getId(), context.descend(summary.title()));
      }
      return;
    }
    if (page == null) {
      page = fetchPage(run, summary.id(), pageLabel(summary, spaceKey), pagePath);
      if (page == null) {
        return;
      }
      if (page.status() == ConfluencePageStatus.TRASHED) {
        discardTrashed(run, existing, pagePath);
        return;
      }
    }
    SourceDocumentContext pageContext =
        new SourceDocumentContext(
            spaceKey,
            page.ancestorTitles().isEmpty()
                ? null
                : String.join(SourceDocumentContext.HIERARCHY_SEPARATOR, page.ancestorTitles()));
    storePage(run, page, pagePath, String.valueOf(page.version()), pageContext);
  }

  /**
   * A page outside the library's space selection is left alone until the next full sync judges its
   * old document: a REJECTED note, counted as skipped. True when the page was rejected.
   */
  private static boolean rejectedAsUnselected(ConfluenceRun run, ConfluencePageSummary summary) {
    String spaceKey = summary.spaceKey();
    if (spaceKey != null && run.selectedKeys.contains(spaceKey)) {
      return false;
    }
    run.events.record(
        IndexingEventCategory.REJECTED,
        pageLabel(summary, spaceKey == null ? "?" : spaceKey) + NOT_SELECTED_SUFFIX,
        summary.id());
    run.progress.recordSkipped();
    return true;
  }

  /**
   * The page as the instance has it now, or {@code null} once the protocol says why not: a 403 or a
   * 404 is "not readable for this account" - no deletion finding either way, a skip; anything else
   * is unreachable and counts as failed. {@code label} opens the note, {@code reference} names it.
   */
  private ConfluencePage fetchPage(ConfluenceRun run, String pageId, String label, String reference)
      throws InterruptedException, ConfluenceAccessException.BudgetExhausted {
    Optional<ConfluencePage> fetched;
    try {
      fetched = run.client.fetchPage(pageId);
    } catch (ConfluenceAccessException.BudgetExhausted e) {
      throw e;
    } catch (ConfluenceAccessException.Forbidden e) {
      fetched = Optional.empty();
    } catch (ConfluenceAccessException e) {
      run.events.record(IndexingEventCategory.UNREACHABLE, label + e.getMessage(), reference);
      run.progress.recordFailed();
      return null;
    }
    if (fetched.isEmpty()) {
      run.events.record(IndexingEventCategory.REJECTED, label + UNREADABLE_PAGE_SUFFIX, reference);
      run.progress.recordSkipped();
      return null;
    }
    return fetched.get();
  }

  /**
   * The positive finding a deletion needs (ADR-0023, Entscheidung 4): the instance itself reports
   * the page trashed, so its document and attachments go and the reconciliation no longer sees it.
   */
  private void discardTrashed(ConfluenceRun run, Optional<Document> existing, String pagePath) {
    existing.ifPresent(
        document -> removeWithAttachments(run, document, TRASHED_MESSAGE, new HashSet<>()));
    run.frame.markAbsent(pagePath);
    run.progress.recordSkipped();
  }

  /**
   * A page under a new identity URL may be the old document of another selected space (Cloud puts
   * the space key into the URL): the instance itself says the page lives elsewhere now, so the old
   * document and its attachments go - a positive finding, not absence (ADR-0023, Entscheidung 4).
   */
  private void removeMovedFrom(ConfluenceRun run, ConfluencePageSummary summary, String newPath) {
    for (String otherKey : run.selectedKeys) {
      if (otherKey.equals(summary.spaceKey())) {
        continue;
      }
      String oldPath = run.client.pageUrl(otherKey, summary.id());
      if (oldPath.equals(newPath)) {
        continue;
      }
      documentRepository
          .findByLibraryIdAndFilePath(run.library.getId(), oldPath)
          .ifPresent(old -> removeWithAttachments(run, old, MOVED_MESSAGE, new HashSet<>()));
    }
  }

  /** Unfinished spaces of an interrupted full sync first, then the already completed ones. */
  static List<ConfluenceSpaceSelection> orderForResumption(
      KnowledgeLibrary library, ConfluenceSyncState state) {
    Set<String> completed = state.isFullSyncInterrupted() ? state.completedSpaceKeys() : Set.of();
    List<ConfluenceSpaceSelection> ordered = new ArrayList<>();
    for (ConfluenceSpaceSelection space : library.getConfluenceSpaces()) {
      if (!completed.contains(space.getSpaceKey())) {
        ordered.add(space);
      }
    }
    for (ConfluenceSpaceSelection space : library.getConfluenceSpaces()) {
      if (completed.contains(space.getSpaceKey())) {
        ordered.add(space);
      }
    }
    return ordered;
  }

  /**
   * Text and attachments of a fetched, current page. The body goes over as it is - {@link
   * ConfluenceDocumentPipeline} owns the macro rules and the cut; the version is the change marker,
   * the creation of the current version the page's Stand (ADR-0023).
   */
  private void storePage(
      ConfluenceRun run,
      ConfluencePage page,
      String pagePath,
      String version,
      SourceDocumentContext pageContext)
      throws InterruptedException, ConfluenceAccessException.BudgetExhausted {
    String storageBody = page.storageBody() == null ? "" : page.storageBody();
    SourceDocumentContext attachmentContext = pageContext.descend(page.title());
    if (storageBody.isBlank()) {
      // A page without text has no document row of its own; its attachments (a page that only
      // carries forms, say) are still indexed - as children of a row a previous run stored, or
      // else without a parent.
      run.events.record(
          IndexingEventCategory.UNSUPPORTED_FORMAT, "Kein Inhalt extrahierbar", pagePath);
      run.progress.recordSkipped();
      attachments.indexAttachments(
          run, page.id(), pagePath, pageDocumentId(run, pagePath), attachmentContext);
      return;
    }
    boolean pageStored;
    try {
      FileProcessingResult result =
          fileProcessingService.ingest(
              DocumentIngest.text(run.library, pagePath, storageBody)
                  .sourceType(DocumentSourceType.CONFLUENCE)
                  .title(page.title())
                  .context(pageContext)
                  .changeMarker(version)
                  .modifiedAt(DocumentProperties.instantToLocalDate(page.lastModified()))
                  .pipelineId(ConfluenceDocumentPipeline.ID)
                  .build(),
              null);
      if (run.frame.recordOutcome(result, pagePath)) {
        log.info("Indexed Confluence page: {}", pagePath);
      }
      // A page whose row exists - stored now, unchanged, or rejected as text-free - carries its
      // attachments; one the quota or the pipeline refused has no row to hang them on.
      pageStored =
          result != FileProcessingResult.QUOTA_EXCEEDED && result != FileProcessingResult.FAILED;
    } catch (Exception e) {
      run.frame.recordFailure(pagePath, e);
      pageStored = false;
    }
    if (pageStored) {
      attachments.indexAttachments(
          run, page.id(), pagePath, pageDocumentId(run, pagePath), attachmentContext);
    }
  }

  /** The page's own row, the parent every attachment is stored under; {@code null} without one. */
  private UUID pageDocumentId(ConfluenceRun run, String pagePath) {
    return documentRepository
        .findByLibraryIdAndFilePath(run.library.getId(), pagePath)
        .map(Document::getId)
        .orElse(null);
  }

  /** "Seite „Titel“ (Space KEY) " - how the protocol names a page. */
  private static String pageLabel(ConfluencePageSummary summary, String spaceKey) {
    return "Seite „" + summary.title() + "“ (Space " + spaceKey + ") ";
  }

  private static boolean isUnchanged(Optional<Document> existing, String version) {
    return existing.isPresent() && existing.get().isUnchangedAt(version);
  }

  /**
   * Deletes {@code document} and every attachment below it, deepest first - {@code
   * fk_documents_parent} refuses a parent whose children still exist, and an attachment can carry
   * children of its own (a {@code .eml} attached to a page). {@code visited} guards a cyclic {@code
   * parent_document_id} chain, never expected from well-formed data.
   */
  private void removeWithAttachments(
      ConfluenceRun run, Document document, String message, Set<UUID> visited) {
    if (!visited.add(document.getId())) {
      return;
    }
    for (Document child : documentRepository.findByParentDocumentId(document.getId())) {
      removeWithAttachments(run, child, message, visited);
    }
    vectorChunkStore.deleteByDocumentId(document.getId());
    documentRepository.delete(document);
    run.events.record(IndexingEventCategory.REMOVED, message, document.getFilePath());
  }

  private static void reportThrottling(ConfluenceClient client, IndexingRunEventRecorder events) {
    ConfluenceRequestMeter meter = client.meter();
    if (meter.throttles() == 0) {
      return;
    }
    Duration waited = meter.throttledTime();
    events.record(
        IndexingEventCategory.RATE_LIMITED,
        "Confluence hat den Lauf "
            + meter.throttles()
            + "-mal gedrosselt (Retry-After); der Lauf hat insgesamt "
            + waited.toSeconds()
            + " Sekunden gewartet statt abzubrechen",
        null);
  }
}
