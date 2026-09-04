package io.opaa.indexing.source.confluence;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.DocumentService;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.IndexingEventCategory;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.IndexingRunCost;
import io.opaa.indexing.IndexingRunEventRecorder;
import io.opaa.indexing.IndexingRunEventRepository;
import io.opaa.indexing.IndexingRunProgress;
import io.opaa.indexing.SourceDocumentContext;
import io.opaa.indexing.StaleDocumentCleanupService;
import io.opaa.indexing.VectorChunkStore;
import io.opaa.indexing.source.IndexingSourceType;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.indexing.source.VanishedDocumentPolicy;
import io.opaa.indexing.source.attachment.AttachmentDownloadLimits;
import io.opaa.indexing.source.attachment.AttachmentIndexer;
import io.opaa.indexing.source.attachment.AttachmentSource;
import io.opaa.library.ConfluenceSpaceSelection;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.sourceaccess.BoundedDownloader;
import java.io.IOException;
import java.nio.file.Files;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;

/**
 * Executes indexing runs for {@link IndexingSourceType#CONFLUENCE} (ADR-0023). The full sync
 * (#1136): every selected space is listed completely - identifiers, titles and versions only, no
 * body expand -, every page whose version changed is fetched individually and handed to {@link
 * FileProcessingService#processConfluencePage}, its attachments are downloaded through the
 * edition-aware client and indexed as children of the page over the generalized attachment path
 * ({@link AttachmentIndexer}, ADR-0022), and once <em>every</em> selected space was listed
 * completely, whatever this library indexed from Confluence before and did not meet again is
 * removed ({@link StaleDocumentCleanupService}). The incremental run (#1139) asks CQL for what
 * changed since the anchor and never reconciles; which of the two a run without a requested mode
 * takes is decided from the library's sync state in {@link #defaultRunMode}.
 *
 * <p><b>What may delete, and what may not</b> (ADR-0023, Entscheidung 4): the credentials are
 * verified before the first listing - Data Center serves an unknown token anonymously with an empty
 * listing, which must never pass for a complete one. A space the token cannot list makes the run's
 * listing incomplete: it is reported, nothing is removed, the bestand of that space stays. A page
 * the token cannot read ({@code 404}) is skipped visibly and stays in the index - a revoked right
 * is no deletion finding. Only {@code trashed}, answered by the instance itself, removes a page
 * (and its attachments) outside the final reconciliation.
 *
 * <p><b>Resumption:</b> the spaces a previous, interrupted full sync completed are recorded in
 * {@link ConfluenceSyncState}; the next run takes the unfinished spaces first. The cheap version
 * check before any body fetch makes re-listing a completed space nearly free, and it has to happen
 * anyway: the reconciliation needs the complete current bestand.
 */
public class ConfluenceIndexingExecutor implements SourceIndexingExecutor {

  private static final Logger log = LoggerFactory.getLogger(ConfluenceIndexingExecutor.class);

  static final String TRASHED_MESSAGE = "In Confluence im Papierkorb, entfernt";
  static final String MOVED_MESSAGE =
      "In Confluence in einen anderen Space verschoben, alter Stand entfernt";

  /**
   * Suffixes of the skip notes (#1138); the notes themselves name space and title - what a reader
   * of the protocol needs to know what the library does not contain, in the consequence's words,
   * not the mechanism's.
   */
  static final String UNREADABLE_PAGE_SUFFIX =
      "ist für das hinterlegte Dienstkonto nicht lesbar oder nicht mehr vorhanden, übersprungen;"
          + " der bereits indizierte Stand bleibt erhalten";

  static final String UNREADABLE_SPACE_SUFFIX =
      "ist für das hinterlegte Dienstkonto nicht lesbar; sein Bestand bleibt bis zur nächsten"
          + " vollständigen Auflistung unverändert";

  private final ConfluenceClientFactory clientFactory;
  private final ConfluenceProperties properties;
  private final FileProcessingService fileProcessingService;
  private final AttachmentIndexer attachmentIndexer;
  private final IndexingJobService indexingJobService;
  private final DocumentRepository documentRepository;
  private final IndexingRunEventRepository indexingRunEventRepository;
  private final LibraryStorageQuotaService storageQuotaService;
  private final StaleDocumentCleanupService staleDocumentCleanupService;
  private final ConfluenceSyncStateRepository syncStateRepository;
  private final VectorChunkStore vectorChunkStore;
  private final Clock clock;

  /**
   * The generalized attachment path's limits for a Confluence attachment: the download is bounded
   * by {@link ConfluenceProperties#maxAttachmentSizeBytes()} before the path ever sees the bytes
   * (see {@link #indexAttachment}), one attachment is handed over per call (no per-page cap of its
   * own - the request budget bounds a run), and a nested attachment (a {@code .eml} attached to a
   * page) descends as deep as every other source.
   */
  private final AttachmentDownloadLimits attachmentLimits;

  public ConfluenceIndexingExecutor(
      ConfluenceClientFactory clientFactory,
      ConfluenceProperties properties,
      FileProcessingService fileProcessingService,
      AttachmentIndexer attachmentIndexer,
      IndexingJobService indexingJobService,
      DocumentRepository documentRepository,
      IndexingRunEventRepository indexingRunEventRepository,
      LibraryStorageQuotaService storageQuotaService,
      StaleDocumentCleanupService staleDocumentCleanupService,
      ConfluenceSyncStateRepository syncStateRepository,
      VectorChunkStore vectorChunkStore,
      Clock clock) {
    this.clientFactory = clientFactory;
    this.properties = properties;
    this.fileProcessingService = fileProcessingService;
    this.attachmentIndexer = attachmentIndexer;
    this.indexingJobService = indexingJobService;
    this.documentRepository = documentRepository;
    this.indexingRunEventRepository = indexingRunEventRepository;
    this.storageQuotaService = storageQuotaService;
    this.staleDocumentCleanupService = staleDocumentCleanupService;
    this.syncStateRepository = syncStateRepository;
    this.vectorChunkStore = vectorChunkStore;
    this.clock = clock;
    this.attachmentLimits =
        new AttachmentDownloadLimits(
            1,
            properties.maxAttachmentSizeBytes(),
            0L,
            properties.userAgent(),
            AttachmentIndexer.DEFAULT_MAX_ATTACHMENT_DEPTH);
  }

  @Override
  public IndexingSourceType sourceType() {
    return IndexingSourceType.CONFLUENCE;
  }

  @Override
  public Map<IndexingRunMode, VanishedDocumentPolicy> runModes() {
    // ADR-0023, Entscheidung 4: the full sync is "vollständig auflistend", the incremental run
    // "ergänzend" - it never removes anything for being absent from its change window (#1139).
    return Map.of(
        IndexingRunMode.FULL,
        VanishedDocumentPolicy.REMOVE_ON_ABSENCE,
        IndexingRunMode.INCREMENTAL,
        VanishedDocumentPolicy.KEEP_ON_ABSENCE);
  }

  /**
   * ADR-0023, Entscheidung 4 ("Betriebsarten im Zeitplan"): the first run, every run after a change
   * of the space selection (KnowledgeLibraryService deletes the state then), a run after an
   * interrupted full sync and every run once {@code fullSyncInterval} has passed since the last
   * completed full sync are full ones; the routine run in between is incremental. Decided from the
   * state at trigger time, so a full run that fell due while an incremental one was running is
   * taken at the next tick, not lost.
   */
  @Override
  public IndexingRunMode defaultRunMode(KnowledgeLibrary library) {
    return syncStateRepository
        .findByLibraryId(library.getId())
        .filter(state -> !state.isFullSyncDue(properties.fullSyncInterval(), clock.instant()))
        .map(state -> IndexingRunMode.INCREMENTAL)
        .orElse(IndexingRunMode.FULL);
  }

  @Override
  @Async("indexingTaskExecutor")
  public void execute(UUID jobId, KnowledgeLibrary targetLibrary, IndexingRunMode runMode) {
    var progress = new IndexingRunProgress(indexingJobService, jobId);
    var events =
        new IndexingRunEventRecorder(indexingRunEventRepository, indexingJobService, jobId);
    if (!runModes().containsKey(runMode)) {
      progress.fail("Betriebsart " + runMode + " wird für diesen Quellentyp nicht unterstützt");
      return;
    }
    Instant startedAt = clock.instant();
    ConfluenceConnection connection;
    try {
      connection = ConfluenceLibraryConnection.of(targetLibrary);
    } catch (ConfluenceLibraryConnection.InvalidConfluenceConfigurationException e) {
      progress.fail(e.getMessage());
      return;
    }
    ConfluenceClient client = null;
    Run run = null;
    String failure = null;
    try {
      client = clientFactory.createForRun(connection);
      // ADR-0023, Entscheidung 2: before the first listing, never after - see the class Javadoc.
      client.verifyCredentials();
      run = new Run(jobId, client, targetLibrary, progress, events);
      if (runMode == IndexingRunMode.INCREMENTAL) {
        incrementalSync(run, startedAt);
      } else {
        fullSync(run, startedAt);
      }
    } catch (ConfluenceAccessException e) {
      log.warn("Confluence run for library {} failed: {}", targetLibrary.getId(), e.getMessage());
      failure = e.getMessage();
    } catch (InterruptedException e) {
      failure = "Lauf unterbrochen";
      Thread.currentThread().interrupt();
    } catch (DataIntegrityViolationException e) {
      // fk_confluence_sync_state_library: the library was deleted while this run was writing.
      log.error("Confluence run failed - target library no longer exists", e);
      failure = "Die Bibliothek wurde während des Laufs gelöscht.";
    } catch (Exception e) {
      log.error("Confluence run for library {} failed unexpectedly", targetLibrary.getId(), e);
      failure = e.getMessage();
    }
    finish(jobId, targetLibrary, client, run, progress, events, startedAt, failure);
  }

  /**
   * The common end of every run: throttling is reported whether the run succeeded or not - a run
   * that the instance slowed down forty times before it failed is exactly what an operator wants to
   * see in the protocol - the cost figures (#1141) are recorded, and one log line names them.
   */
  private void finish(
      UUID jobId,
      KnowledgeLibrary library,
      ConfluenceClient client,
      Run run,
      IndexingRunProgress progress,
      IndexingRunEventRecorder events,
      Instant startedAt,
      String failure) {
    if (client != null) {
      reportThrottling(client, events);
      ConfluenceRequestMeter meter = client.meter();
      boolean incomplete = failure == null && run != null && run.incomplete;
      indexingJobService.recordRunMetrics(
          jobId,
          new IndexingRunCost(
              meter.requests(),
              meter.throttles(),
              meter.throttledTime().toMillis(),
              progress.attachmentsProcessed(),
              progress.attachmentsSkipped(),
              progress.attachmentsFailed(),
              incomplete));
      log.info(
          "Confluence run {} for library {}: {} requests, {} throttles ({} s waited), {} attachments"
              + " indexed, {} s elapsed, incomplete={}, failure={}",
          jobId,
          library.getId(),
          meter.requests(),
          meter.throttles(),
          meter.throttledTime().toSeconds(),
          progress.attachmentsProcessed(),
          Duration.between(startedAt, clock.instant()).toSeconds(),
          incomplete,
          failure);
    }
    events.finalizeRun();
    if (failure == null) {
      progress.complete();
    } else {
      progress.fail(failure);
    }
  }

  /** One protocol note when the budget ran out (#1141), naming where the next run continues. */
  private static void recordBudgetExhausted(
      Run run, ConfluenceAccessException.BudgetExhausted e, String continuation) {
    run.incomplete = true;
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
  }

  /** Everything one run shares across its spaces, pages and attachments. */
  private static final class Run {
    final UUID jobId;
    final ConfluenceClient client;
    final KnowledgeLibrary library;
    final IndexingRunProgress progress;
    final IndexingRunEventRecorder events;

    /** {@code file_path} of every page and attachment met in this run - the reconciliation set. */
    final Set<String> currentPaths = new HashSet<>();

    /**
     * The subset of {@link #currentPaths} whose own attachments were freshly enumerated this run -
     * a page whose attachment list was fetched, an attachment the attachment path re-parsed
     * (ADR-0022, Entscheidung 3). The attachments of every other path in {@link #currentPaths} (a
     * page skipped, unreadable or failed this run, an attachment unchanged by version) are
     * preserved from the database before the reconciliation, see {@link
     * StaleDocumentCleanupService#foldInPreservedAttachmentPaths}.
     */
    final Set<String> reprocessedPaths = new HashSet<>();

    /** False once any selected space or attachment list could not be listed completely. */
    boolean listingComplete = true;

    /**
     * True once the request budget ran out (#1141): the run ends in an orderly way, covers what it
     * covered, and the next run continues - a full sync with the unfinished spaces, an incremental
     * run with the same window.
     */
    boolean incomplete;

    /**
     * True when this full sync continues an interrupted one (#1141): a page already stored at the
     * listed version then costs no call at all - its attachments were listed by the run that stored
     * it, and a chain of resumed runs must converge, not re-spend its budget on the done part.
     */
    boolean resumed;

    int total;

    Run(
        UUID jobId,
        ConfluenceClient client,
        KnowledgeLibrary library,
        IndexingRunProgress progress,
        IndexingRunEventRecorder events) {
      this.jobId = jobId;
      this.client = client;
      this.library = library;
      this.progress = progress;
      this.events = events;
    }
  }

  private void fullSync(Run run, Instant startedAt)
      throws ConfluenceAccessException, InterruptedException {
    UUID libraryId = run.library.getId();
    ConfluenceSyncState state =
        syncStateRepository
            .findByLibraryId(libraryId)
            .orElseGet(() -> new ConfluenceSyncState(libraryId));
    List<ConfluenceSpaceSelection> spaces = orderForResumption(run.library, state);
    run.resumed = state.isFullSyncInterrupted();
    state.beginFullSync(run.jobId);
    state = syncStateRepository.save(state);

    for (ConfluenceSpaceSelection space : spaces) {
      String key = space.getSpaceKey();
      List<ConfluencePageSummary> pages;
      try {
        pages = run.client.listPages(key);
      } catch (ConfluenceAccessException.BudgetExhausted e) {
        // #1141: the state already holds every completed space - the next run starts with this one
        recordBudgetExhausted(run, e, "der nächste Lauf setzt bei Space " + key + " fort");
        return;
      } catch (ConfluenceAccessException.Forbidden | ConfluenceAccessException.NotFound e) {
        // ADR-0023, Entscheidung 4: a revoked right is no deletion finding - the run says so and
        // leaves this space's bestand alone.
        log.warn(
            "Confluence space {} not readable for library {}: {}", key, libraryId, e.getMessage());
        run.events.record(
            IndexingEventCategory.REJECTED, "Space " + key + " " + UNREADABLE_SPACE_SUFFIX, key);
        run.listingComplete = false;
        continue;
      }
      run.total += pages.size();
      run.progress.setTotal(run.total);
      run.progress.report();
      for (ConfluencePageSummary page : pages) {
        try {
          processPage(run, key, page);
        } catch (ConfluenceAccessException.BudgetExhausted e) {
          // #1141: pages already stored keep their version, so the next run re-lists this space
          // cheaply (listing entries only) and fetches only what is still missing
          recordBudgetExhausted(
              run,
              e,
              "der nächste Lauf setzt bei Space "
                  + key
                  + " fort; bereits gespeicherte Seiten kosten dabei keinen Abruf");
          return;
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
      return;
    }
    try {
      // ADR-0022, Entscheidung 3 (mirroring UrlIndexingExecutor): the attachments of a page this
      // run did not list again - skipped in a resumed run, unreadable, failed - and the children
      // of an attachment it did not re-parse are no finding and stay; only an attachment missing
      // from a freshly fetched list, or not re-reported by a re-parsed parent, is gone.
      StaleDocumentCleanupService.foldInPreservedAttachmentPaths(
          documentRepository.findByLibraryIdAndSourceType(libraryId, DocumentSourceType.CONFLUENCE),
          run.currentPaths,
          run.reprocessedPaths);
      staleDocumentCleanupService.cleanupVanished(
          run.library,
          DocumentSourceType.CONFLUENCE,
          run.currentPaths,
          run.events,
          this,
          IndexingRunMode.FULL);
    } catch (Exception e) {
      // Without the reconciliation the full sync is not complete: the state stays open, so the
      // next run reconciles again instead of anchoring an incremental run on a stale bestand.
      log.warn("Failed to clean up vanished CONFLUENCE documents for library {}", libraryId, e);
      run.events.record(
          IndexingEventCategory.ERROR,
          "Abgleich des Bestands fehlgeschlagen; der nächste Lauf holt ihn nach",
          null);
      return;
    }
    state.completeFullSync(startedAt, clock.instant());
    syncStateRepository.save(state);
  }

  /**
   * The incremental run (#1139): asks CQL for the identifiers of the pages in the selected spaces
   * modified since the anchor minus the overlap, fetches each one individually and takes over what
   * is new or changed. It never calls the reconciliation - a page absent from this window is not
   * evidence of anything (ADR-0023, Entscheidung 4) - and it removes only what the instance itself
   * reports as trashed. The anchor moves to this run's start only when the run failed nothing, so
   * no change window is ever lost to an aborted run.
   */
  private void incrementalSync(Run run, Instant startedAt)
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
    Set<String> selectedKeys = new HashSet<>();
    for (ConfluenceSpaceSelection space : run.library.getConfluenceSpaces()) {
      selectedKeys.add(space.getSpaceKey());
    }
    Instant since = state.getIncrementalAnchor().minus(properties.incrementalOverlap());
    List<ConfluencePageSummary> changed;
    try {
      changed = run.client.searchPagesModifiedSince(selectedKeys, since);
    } catch (ConfluenceAccessException.BudgetExhausted e) {
      recordBudgetExhausted(run, e, "der nächste Lauf durchsucht dasselbe Änderungsfenster erneut");
      return;
    }
    run.total = changed.size();
    run.progress.setTotal(run.total);
    run.progress.report();
    for (ConfluencePageSummary summary : changed) {
      try {
        processChangedPage(run, summary, selectedKeys);
      } catch (ConfluenceAccessException.BudgetExhausted e) {
        // #1141: the anchor stays, so the next run searches the same window again
        recordBudgetExhausted(
            run, e, "der nächste Lauf durchsucht dasselbe Änderungsfenster erneut");
        return;
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
  }

  /**
   * One page the change search named. The version comes with the search, so an unchanged page
   * (re-read through the overlap) costs no body fetch (ADR-0017, Entscheidung 2); its attachments
   * are still listed because they do not bump the page's version. A page that moved between two
   * selected spaces changes its identity URL on Cloud - the document under the old URL is removed
   * as a positive finding (the instance says where the page is now), never as absence.
   */
  private void processChangedPage(Run run, ConfluencePageSummary summary, Set<String> selectedKeys)
      throws InterruptedException, ConfluenceAccessException.BudgetExhausted {
    String spaceKey = summary.spaceKey();
    if (spaceKey == null || !selectedKeys.contains(spaceKey)) {
      // moved out of the selection: the old document stays until the next full run judges it
      run.events.record(
          IndexingEventCategory.REJECTED,
          pageLabel(summary, spaceKey == null ? "?" : spaceKey)
              + "liegt in einem nicht ausgewählten Space; der bisherige Stand bleibt bis zum"
              + " nächsten Vollabgleich",
          summary.id());
      run.progress.recordSkipped();
      return;
    }
    String pagePath = run.client.pageUrl(spaceKey, summary.id());
    Optional<Document> existing =
        documentRepository.findByLibraryIdAndFilePath(run.library.getId(), pagePath);
    if (existing.isEmpty()) {
      removeMovedFrom(run, summary, selectedKeys, pagePath);
    }
    String version = String.valueOf(summary.version());
    if (isUnchanged(existing, version)) {
      run.progress.recordSkipped();
      SourceDocumentContext context =
          new SourceDocumentContext(spaceKey, existing.get().getSourceHierarchyPath());
      indexAttachments(
          run, summary.id(), pagePath, existing.get().getId(), context.descend(summary.title()));
      return;
    }
    Optional<ConfluencePage> fetched;
    try {
      fetched = run.client.fetchPage(summary.id());
    } catch (ConfluenceAccessException.BudgetExhausted e) {
      throw e;
    } catch (ConfluenceAccessException.Forbidden e) {
      run.events.record(
          IndexingEventCategory.REJECTED,
          pageLabel(summary, spaceKey) + UNREADABLE_PAGE_SUFFIX,
          pagePath);
      run.progress.recordSkipped();
      return;
    } catch (ConfluenceAccessException e) {
      run.events.record(
          IndexingEventCategory.UNREACHABLE,
          pageLabel(summary, spaceKey) + e.getMessage(),
          pagePath);
      run.progress.recordFailed();
      return;
    }
    if (fetched.isEmpty()) {
      // a 404 is "gone" as much as "not readable" - no deletion finding either way
      run.events.record(
          IndexingEventCategory.REJECTED,
          pageLabel(summary, spaceKey) + UNREADABLE_PAGE_SUFFIX,
          pagePath);
      run.progress.recordSkipped();
      return;
    }
    applyFetchedPage(run, fetched.get(), pagePath, existing);
  }

  /**
   * What a freshly fetched page means for the index: trashed is the positive finding a deletion
   * needs - the instance says so itself (ADR-0023, Entscheidung 4) - everything else is stored.
   */
  private void applyFetchedPage(
      Run run, ConfluencePage page, String pagePath, Optional<Document> existing)
      throws InterruptedException, ConfluenceAccessException.BudgetExhausted {
    if (page.status() == ConfluencePageStatus.TRASHED) {
      removeTrashed(run, existing, pagePath);
      run.progress.recordSkipped();
      return;
    }
    SourceDocumentContext pageContext =
        new SourceDocumentContext(
            page.spaceKey(),
            page.ancestorTitles().isEmpty()
                ? null
                : String.join(SourceDocumentContext.HIERARCHY_SEPARATOR, page.ancestorTitles()));
    storePage(run, page, pagePath, String.valueOf(page.version()), pageContext);
  }

  /**
   * The webhook run (#1140): fetches exactly {@code pageIds} and applies what the instance answers
   * - a page it reports as trashed is removed with its attachments, a changed page is re-indexed,
   * an unchanged one only has its attachments checked, a 404 or 403 leaves the index untouched (no
   * positive finding, ADR-0023, Entscheidung 4). Never a listing, never a cleanup, and the
   * incremental anchor stays where it is: the next incremental run re-reads these pages once more,
   * which costs a listing entry each and nothing else.
   */
  @Async("indexingTaskExecutor")
  public void refreshPages(UUID jobId, KnowledgeLibrary targetLibrary, Set<String> pageIds) {
    var progress = new IndexingRunProgress(indexingJobService, jobId);
    var events =
        new IndexingRunEventRecorder(indexingRunEventRepository, indexingJobService, jobId);
    Instant startedAt = clock.instant();
    ConfluenceConnection connection;
    try {
      connection = ConfluenceLibraryConnection.of(targetLibrary);
    } catch (ConfluenceLibraryConnection.InvalidConfluenceConfigurationException e) {
      progress.fail(e.getMessage());
      return;
    }
    ConfluenceClient client = null;
    Run run = null;
    String failure = null;
    try {
      client = clientFactory.createForRun(connection);
      client.verifyCredentials();
      run = new Run(jobId, client, targetLibrary, progress, events);
      progress.setTotal(pageIds.size());
      Set<String> selectedKeys = new HashSet<>();
      for (ConfluenceSpaceSelection selection : targetLibrary.getConfluenceSpaces()) {
        selectedKeys.add(selection.getSpaceKey());
      }
      for (String pageId : pageIds.stream().sorted().toList()) {
        try {
          refreshPage(run, pageId, selectedKeys);
        } catch (ConfluenceAccessException.BudgetExhausted e) {
          recordBudgetExhausted(run, e, "die übrigen gemeldeten Seiten nimmt der nächste Lauf auf");
          break;
        }
        run.progress.report();
      }
    } catch (ConfluenceAccessException e) {
      log.warn(
          "Confluence webhook run for library {} failed: {}",
          targetLibrary.getId(),
          e.getMessage());
      failure = e.getMessage();
    } catch (InterruptedException e) {
      failure = "Lauf unterbrochen";
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      log.error(
          "Confluence webhook run for library {} failed unexpectedly", targetLibrary.getId(), e);
      failure = e.getMessage();
    }
    finish(jobId, targetLibrary, client, run, progress, events, startedAt, failure);
  }

  private void refreshPage(Run run, String pageId, Set<String> selectedKeys)
      throws InterruptedException, ConfluenceAccessException.BudgetExhausted {
    String label = "Seite " + pageId + " (per Webhook gemeldet) ";
    Optional<ConfluencePage> fetched;
    try {
      fetched = run.client.fetchPage(pageId);
    } catch (ConfluenceAccessException.BudgetExhausted e) {
      throw e;
    } catch (ConfluenceAccessException.Forbidden e) {
      run.events.record(IndexingEventCategory.REJECTED, label + UNREADABLE_PAGE_SUFFIX, pageId);
      run.progress.recordSkipped();
      return;
    } catch (ConfluenceAccessException e) {
      run.events.record(IndexingEventCategory.UNREACHABLE, label + e.getMessage(), pageId);
      run.progress.recordFailed();
      return;
    }
    if (fetched.isEmpty()) {
      run.events.record(IndexingEventCategory.REJECTED, label + UNREADABLE_PAGE_SUFFIX, pageId);
      run.progress.recordSkipped();
      return;
    }
    ConfluencePage page = fetched.get();
    String spaceKey = page.spaceKey();
    String pagePath = run.client.pageUrl(spaceKey, page.id());
    Optional<Document> existing =
        documentRepository.findByLibraryIdAndFilePath(run.library.getId(), pagePath);
    if (page.status() == ConfluencePageStatus.TRASHED) {
      applyFetchedPage(run, page, pagePath, existing);
      return;
    }
    if (spaceKey == null || !selectedKeys.contains(spaceKey)) {
      run.events.record(
          IndexingEventCategory.REJECTED,
          "Seite „"
              + page.title()
              + "“ (Space "
              + (spaceKey == null ? "?" : spaceKey)
              + ") liegt in einem nicht ausgewählten Space; der bisherige Stand bleibt bis zum"
              + " nächsten Vollabgleich",
          page.id());
      run.progress.recordSkipped();
      return;
    }
    ConfluencePageSummary summary =
        new ConfluencePageSummary(page.id(), spaceKey, page.title(), page.version(), null);
    if (existing.isEmpty()) {
      removeMovedFrom(run, summary, selectedKeys, pagePath);
    }
    if (isUnchanged(existing, String.valueOf(page.version()))) {
      run.progress.recordSkipped();
      SourceDocumentContext context =
          new SourceDocumentContext(spaceKey, existing.get().getSourceHierarchyPath());
      indexAttachments(
          run, page.id(), pagePath, existing.get().getId(), context.descend(page.title()));
      return;
    }
    applyFetchedPage(run, page, pagePath, existing);
  }

  /**
   * A page under a new identity URL may be the old document of another selected space (Cloud puts
   * the space key into the URL): the instance itself says the page lives elsewhere now, so the old
   * document and its attachments go - a positive finding, not absence (ADR-0023, Entscheidung 4).
   */
  private void removeMovedFrom(
      Run run, ConfluencePageSummary summary, Set<String> selectedKeys, String newPath) {
    for (String otherKey : selectedKeys) {
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

  private void processPage(Run run, String spaceKey, ConfluencePageSummary summary)
      throws InterruptedException, ConfluenceAccessException.BudgetExhausted {
    String pagePath = run.client.pageUrl(spaceKey, summary.id());
    run.currentPaths.add(pagePath);
    Optional<Document> existing =
        documentRepository.findByLibraryIdAndFilePath(run.library.getId(), pagePath);
    String version = String.valueOf(summary.version());
    if (isUnchanged(existing, version)) {
      // ADR-0017, Entscheidung 2: the version is checked before any body is fetched. Attachments
      // do not bump a page's version, so they are listed regardless - except in a resumed full
      // sync (#1141), where the done part must cost nothing: new attachments of a page unchanged
      // since the interrupted run reach the index with the next complete full sync.
      run.progress.recordSkipped();
      if (run.resumed) {
        return;
      }
      SourceDocumentContext context =
          new SourceDocumentContext(spaceKey, existing.get().getSourceHierarchyPath());
      indexAttachments(
          run, summary.id(), pagePath, existing.get().getId(), context.descend(summary.title()));
      return;
    }
    Optional<ConfluencePage> fetched;
    try {
      fetched = run.client.fetchPage(summary.id());
    } catch (ConfluenceAccessException.BudgetExhausted e) {
      throw e;
    } catch (ConfluenceAccessException.Forbidden e) {
      // a 403 on the page is the same finding as a 404: not readable for this account (#1138)
      run.events.record(
          IndexingEventCategory.REJECTED,
          pageLabel(summary, spaceKey) + UNREADABLE_PAGE_SUFFIX,
          pagePath);
      run.progress.recordSkipped();
      return;
    } catch (ConfluenceAccessException e) {
      run.events.record(
          IndexingEventCategory.UNREACHABLE,
          pageLabel(summary, spaceKey) + e.getMessage(),
          pagePath);
      run.progress.recordFailed();
      return;
    }
    if (fetched.isEmpty()) {
      // #1138: visible, not silent - and named by space and title, so the protocol tells a reader
      // what the library does not contain, not just that something was skipped. Its known
      // attachments stay: the page is in currentPaths without being in reprocessedPaths.
      run.events.record(
          IndexingEventCategory.REJECTED,
          pageLabel(summary, spaceKey) + UNREADABLE_PAGE_SUFFIX,
          pagePath);
      run.progress.recordSkipped();
      return;
    }
    ConfluencePage page = fetched.get();
    if (page.status() == ConfluencePageStatus.TRASHED) {
      // The positive finding a deletion needs (ADR-0023, Entscheidung 4).
      removeTrashed(run, existing, pagePath);
      run.currentPaths.remove(pagePath);
      run.progress.recordSkipped();
      return;
    }
    SourceDocumentContext pageContext =
        new SourceDocumentContext(
            spaceKey,
            page.ancestorTitles().isEmpty()
                ? null
                : String.join(SourceDocumentContext.HIERARCHY_SEPARATOR, page.ancestorTitles()));
    storePage(run, page, pagePath, version, pageContext);
  }

  /**
   * Text and attachments of a fetched, current page - shared by the full and the incremental run.
   */
  private void storePage(
      Run run,
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
      indexAttachments(run, page.id(), pagePath, pageDocumentId(run, pagePath), attachmentContext);
      return;
    }
    boolean pageStored;
    try {
      // The body goes over as it is; ConfluenceDocumentPipeline (#1137) owns the macro rules and
      // the structure-preserving cut.
      FileProcessingResult result =
          fileProcessingService.processConfluencePage(
              storageBody, page.title(), pagePath, version, pageContext, run.library);
      pageStored = recordPageResult(run, result, pagePath);
    } catch (Exception e) {
      log.error("Failed to process Confluence page {}", pagePath, e);
      run.events.record(IndexingEventCategory.ERROR, "Verarbeitung fehlgeschlagen", pagePath);
      run.progress.recordFailed();
      pageStored = false;
    }
    // A page this run could not store is no finding about its attachments: it stays in
    // currentPaths without entering reprocessedPaths, so the reconciliation preserves them
    // (ADR-0023, Entscheidung 4: deletion needs a positive finding).
    if (pageStored) {
      indexAttachments(run, page.id(), pagePath, pageDocumentId(run, pagePath), attachmentContext);
    }
  }

  /** The page's own row, the parent every attachment is stored under; {@code null} without one. */
  private UUID pageDocumentId(Run run, String pagePath) {
    return documentRepository
        .findByLibraryIdAndFilePath(run.library.getId(), pagePath)
        .map(Document::getId)
        .orElse(null);
  }

  /** "Seite „Titel“ (Space KEY) " - how the protocol names a page (#1138). */
  private static String pageLabel(ConfluencePageSummary summary, String spaceKey) {
    return "Seite „" + summary.title() + "“ (Space " + spaceKey + ") ";
  }

  private boolean recordPageResult(Run run, FileProcessingResult result, String pagePath) {
    switch (result) {
      case QUOTA_EXCEEDED -> {
        run.events.record(
            IndexingEventCategory.REJECTED,
            storageQuotaService.quotaExceededMessage(run.library.getId()),
            pagePath);
        run.progress.recordSkipped();
        return false;
      }
      case NO_EXTRACTABLE_TEXT -> {
        run.events.record(
            IndexingEventCategory.REJECTED, DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE, pagePath);
        run.progress.recordSkipped();
        return true;
      }
      case FAILED -> {
        run.events.record(IndexingEventCategory.ERROR, "Verarbeitung fehlgeschlagen", pagePath);
        run.progress.recordFailed();
        return false;
      }
      case SKIPPED -> {
        run.progress.recordSkipped();
        return true;
      }
      default -> {
        run.progress.recordProcessed();
        log.info("Indexed Confluence page: {}", pagePath);
        return true;
      }
    }
  }

  private static boolean isUnchanged(Optional<Document> existing, String version) {
    return existing.isPresent()
        && existing.get().getStatus() == DocumentStatus.INDEXED
        && version.equals(existing.get().getLastModifiedRemote());
  }

  /**
   * Removes a page the instance itself reports as trashed, together with its attachment documents
   * (ADR-0022, Entscheidung 3: no database cascade, the caller removes them explicitly).
   */
  private void removeTrashed(Run run, Optional<Document> page, String pagePath) {
    page.ifPresent(
        document -> removeWithAttachments(run, document, TRASHED_MESSAGE, new HashSet<>()));
  }

  /**
   * Deletes {@code document} and every attachment below it, deepest first - {@code
   * fk_documents_parent} refuses a parent whose children still exist, and an attachment can carry
   * children of its own (a {@code .eml} attached to a page). {@code visited} guards a cyclic {@code
   * parent_document_id} chain, never expected from well-formed data.
   */
  private void removeWithAttachments(
      Run run, Document document, String message, Set<UUID> visited) {
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

  /**
   * Lists and indexes the attachments of one page. Every listed attachment enters {@code
   * currentPaths} whether or not it is (re)indexed; the page enters {@code reprocessedPaths} once
   * its list was fetched, so an attachment missing from it is a deletion finding for the
   * reconciliation. An unchanged attachment (version) is skipped before any download.
   *
   * @param pageDocumentId the page's own row, the parent of every attachment (ADR-0022,
   *     Entscheidung 4); {@code null} for a page without a row of its own
   */
  private void indexAttachments(
      Run run, String pageId, String pagePath, UUID pageDocumentId, SourceDocumentContext context)
      throws InterruptedException, ConfluenceAccessException.BudgetExhausted {
    List<ConfluenceAttachment> attachments;
    try {
      attachments = run.client.listAttachments(pageId);
    } catch (ConfluenceAccessException.BudgetExhausted e) {
      throw e;
    } catch (ConfluenceAccessException e) {
      // Without the list, this page's attachments would look vanished to the reconciliation.
      run.events.record(
          IndexingEventCategory.UNREACHABLE,
          "Anhänge nicht auflistbar: " + e.getMessage(),
          pagePath);
      run.listingComplete = false;
      return;
    }
    run.reprocessedPaths.add(pagePath);
    for (ConfluenceAttachment attachment : attachments) {
      String path = attachment.stableUrl();
      run.currentPaths.add(path);
      Optional<Document> existing =
          documentRepository.findByLibraryIdAndFilePath(run.library.getId(), path);
      if (isUnchanged(existing, String.valueOf(attachment.version()))) {
        run.progress.recordAttachment(IndexingRunProgress.AttachmentOutcome.SKIPPED);
        continue;
      }
      indexAttachment(run, attachment, path, pagePath, pageDocumentId, context);
    }
  }

  /**
   * One attachment over the generalized attachment path (ADR-0022, #1137). The download itself
   * stays with the edition-aware {@link ConfluenceClient}: it owns the credentials (which never
   * leave {@code ConfluenceHttp}), the redirect policy Cloud's media service needs, the request
   * budget and the meter (#1141), and maps {@code 403}/{@code 404} to their own findings - the
   * generic {@link AttachmentSource.Download} cannot do any of that. Everything after the bytes -
   * format admission, the document row as a child of the page with the page's context, checksum
   * deduplication, the version as change marker, nested attachments, run events and the
   * reconciliation bookkeeping - is {@link AttachmentIndexer}'s, the same as for RSS and Mail.
   */
  private void indexAttachment(
      Run run,
      ConfluenceAttachment attachment,
      String path,
      String pagePath,
      UUID pageDocumentId,
      SourceDocumentContext context)
      throws InterruptedException, ConfluenceAccessException.BudgetExhausted {
    BoundedDownloader.DownloadedFile downloaded = null;
    try {
      downloaded = run.client.downloadAttachment(attachment);
      ConfluenceAttachmentAccess access =
          new ConfluenceAttachmentAccess(
              run.library,
              run.events,
              run.progress,
              context,
              run.currentPaths,
              run.reprocessedPaths);
      attachmentIndexer.indexAll(
          access,
          List.of(
              new AttachmentSource.LocalFile(
                  downloaded.path(),
                  attachment.fileName(),
                  path,
                  String.valueOf(attachment.version()))),
          pageDocumentId,
          pagePath,
          DocumentSourceType.CONFLUENCE,
          attachmentLimits);
      if (!access.anyProcessed()) {
        // Unchanged content, unsupported or rejected: skipped. Quota, a read error or a failed
        // pipeline: failed - the path itself has already recorded the event.
        run.progress.recordAttachment(
            access.anyDeferred()
                ? IndexingRunProgress.AttachmentOutcome.FAILED
                : IndexingRunProgress.AttachmentOutcome.SKIPPED);
      }
    } catch (BoundedDownloader.AttachmentTooLargeException e) {
      run.events.record(
          IndexingEventCategory.REJECTED, "Anhang überschreitet die Größengrenze", path);
      run.progress.recordAttachment(IndexingRunProgress.AttachmentOutcome.SKIPPED);
    } catch (ConfluenceAccessException.BudgetExhausted e) {
      throw e;
    } catch (ConfluenceAccessException e) {
      run.events.record(IndexingEventCategory.UNREACHABLE, e.getMessage(), path);
      run.progress.recordAttachment(IndexingRunProgress.AttachmentOutcome.FAILED);
    } finally {
      if (downloaded != null) {
        try {
          Files.deleteIfExists(downloaded.path());
        } catch (IOException e) {
          log.debug("Could not delete temporary attachment file {}", downloaded.path(), e);
        }
      }
    }
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
