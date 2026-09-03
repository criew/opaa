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
import io.opaa.indexing.IndexingRunEventRecorder;
import io.opaa.indexing.IndexingRunEventRepository;
import io.opaa.indexing.IndexingRunProgress;
import io.opaa.indexing.SourceDocumentContext;
import io.opaa.indexing.StaleDocumentCleanupService;
import io.opaa.indexing.SupportedDocumentFormats;
import io.opaa.indexing.VectorChunkStore;
import io.opaa.indexing.source.IndexingSourceType;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.indexing.source.VanishedDocumentPolicy;
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
 * FileProcessingService#processConfluencePage}, its attachments are downloaded and indexed as
 * documents of their own (ADR-0022), and once <em>every</em> selected space was listed completely,
 * whatever this library indexed from Confluence before and did not meet again is removed ({@link
 * StaleDocumentCleanupService}). The incremental run is #1139's; until then this executor declares
 * {@link IndexingRunMode#FULL} alone.
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

  /** Suffix of the per-page skip note; the note itself names space and title (#1138). */
  static final String UNREADABLE_PAGE_MESSAGE =
      "ist für das hinterlegte Token nicht lesbar oder nicht mehr vorhanden, übersprungen (kein"
          + " Löschbefund)";

  static final String UNREADABLE_SPACE_MESSAGE =
      "ist für das hinterlegte Token nicht lesbar; sein Bestand bleibt bis zur nächsten"
          + " vollständigen Auflistung unverändert";

  private final ConfluenceClientFactory clientFactory;
  private final FileProcessingService fileProcessingService;
  private final IndexingJobService indexingJobService;
  private final DocumentRepository documentRepository;
  private final IndexingRunEventRepository indexingRunEventRepository;
  private final LibraryStorageQuotaService storageQuotaService;
  private final StaleDocumentCleanupService staleDocumentCleanupService;
  private final ConfluenceSyncStateRepository syncStateRepository;
  private final VectorChunkStore vectorChunkStore;
  private final Clock clock;

  public ConfluenceIndexingExecutor(
      ConfluenceClientFactory clientFactory,
      FileProcessingService fileProcessingService,
      IndexingJobService indexingJobService,
      DocumentRepository documentRepository,
      IndexingRunEventRepository indexingRunEventRepository,
      LibraryStorageQuotaService storageQuotaService,
      StaleDocumentCleanupService staleDocumentCleanupService,
      ConfluenceSyncStateRepository syncStateRepository,
      VectorChunkStore vectorChunkStore,
      Clock clock) {
    this.clientFactory = clientFactory;
    this.fileProcessingService = fileProcessingService;
    this.indexingJobService = indexingJobService;
    this.documentRepository = documentRepository;
    this.indexingRunEventRepository = indexingRunEventRepository;
    this.storageQuotaService = storageQuotaService;
    this.staleDocumentCleanupService = staleDocumentCleanupService;
    this.syncStateRepository = syncStateRepository;
    this.vectorChunkStore = vectorChunkStore;
    this.clock = clock;
  }

  @Override
  public IndexingSourceType sourceType() {
    return IndexingSourceType.CONFLUENCE;
  }

  @Override
  public Map<IndexingRunMode, VanishedDocumentPolicy> runModes() {
    // ADR-0023, Entscheidung 4: the full sync is "vollständig auflistend"; the incremental,
    // "ergänzende" run joins with #1139.
    return Map.of(IndexingRunMode.FULL, VanishedDocumentPolicy.REMOVE_ON_ABSENCE);
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
    String failure = null;
    try {
      client = clientFactory.create(connection);
      // ADR-0023, Entscheidung 2: before the first listing, never after - see the class Javadoc.
      client.verifyCredentials();
      fullSync(new Run(jobId, client, targetLibrary, progress, events), startedAt);
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
    // Throttling is reported whether the run succeeded or not - a run that the instance slowed
    // down forty times before it failed is exactly what an operator wants to see in the protocol.
    if (client != null) {
      reportThrottling(client, events);
    }
    events.finalizeRun();
    if (failure == null) {
      progress.complete();
    } else {
      progress.fail(failure);
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

    /** False once any selected space or attachment list could not be listed completely. */
    boolean listingComplete = true;

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
    state.beginFullSync(run.jobId);
    state = syncStateRepository.save(state);

    for (ConfluenceSpaceSelection space : spaces) {
      String key = space.getSpaceKey();
      List<ConfluencePageSummary> pages;
      try {
        pages = run.client.listPages(key);
      } catch (ConfluenceAccessException.Forbidden | ConfluenceAccessException.NotFound e) {
        // ADR-0023, Entscheidung 4: a revoked right is no deletion finding - the run says so and
        // leaves this space's bestand alone.
        log.warn(
            "Confluence space {} not readable for library {}: {}", key, libraryId, e.getMessage());
        run.events.record(
            IndexingEventCategory.REJECTED, "Space " + key + " " + UNREADABLE_SPACE_MESSAGE, key);
        run.listingComplete = false;
        continue;
      }
      run.total += pages.size();
      run.progress.setTotal(run.total);
      run.progress.report();
      for (ConfluencePageSummary page : pages) {
        processPage(run, key, page);
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
    state.completeFullSync(startedAt);
    syncStateRepository.save(state);
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
      throws InterruptedException {
    String pagePath = run.client.pageUrl(spaceKey, summary.id());
    run.currentPaths.add(pagePath);
    Optional<Document> existing =
        documentRepository.findByLibraryIdAndFilePath(run.library.getId(), pagePath);
    String version = String.valueOf(summary.version());
    if (isUnchanged(existing, version)) {
      // ADR-0017, Entscheidung 2: the version is checked before any body is fetched. Attachments
      // do not bump a page's version, so they are listed regardless.
      run.progress.recordSkipped();
      SourceDocumentContext context =
          new SourceDocumentContext(spaceKey, existing.get().getSourceHierarchyPath());
      indexAttachments(run, summary.id(), pagePath, context.descend(summary.title()));
      return;
    }
    Optional<ConfluencePage> fetched;
    try {
      fetched = run.client.fetchPage(summary.id());
    } catch (ConfluenceAccessException e) {
      run.events.record(IndexingEventCategory.UNREACHABLE, e.getMessage(), pagePath);
      run.progress.recordFailed();
      keepKnownAttachments(run, pagePath);
      return;
    }
    if (fetched.isEmpty()) {
      // #1138: visible, not silent - and named by space and title, so the protocol tells a reader
      // what the library does not contain, not just that something was skipped.
      run.events.record(
          IndexingEventCategory.REJECTED,
          "Seite „" + summary.title() + "“ (Space " + spaceKey + ") " + UNREADABLE_PAGE_MESSAGE,
          pagePath);
      run.progress.recordSkipped();
      keepKnownAttachments(run, pagePath);
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
    String text = ConfluenceStorageText.toPlainText(page.storageBody());
    if (text.isBlank()) {
      run.events.record(
          IndexingEventCategory.UNSUPPORTED_FORMAT, "Kein Inhalt extrahierbar", pagePath);
      run.progress.recordSkipped();
      indexAttachments(run, page.id(), pagePath, pageContext.descend(page.title()));
      return;
    }
    boolean pageStored;
    try {
      FileProcessingResult result =
          fileProcessingService.processConfluencePage(
              text, page.title(), pagePath, version, pageContext, run.library);
      pageStored = recordPageResult(run, result, pagePath);
    } catch (Exception e) {
      log.error("Failed to process Confluence page {}", pagePath, e);
      run.events.record(IndexingEventCategory.ERROR, "Verarbeitung fehlgeschlagen", pagePath);
      run.progress.recordFailed();
      pageStored = false;
    }
    if (pageStored) {
      indexAttachments(run, page.id(), pagePath, pageContext.descend(page.title()));
    } else {
      keepKnownAttachments(run, pagePath);
    }
  }

  /**
   * A page this run could not (or did not) process is no finding about its attachments - their
   * documents stay in the reconciliation set, or the cleanup would remove them for the wrong reason
   * (ADR-0023, Entscheidung 4: deletion needs a positive finding).
   */
  private void keepKnownAttachments(Run run, String pagePath) {
    for (Document attachment :
        documentRepository.findByLibraryIdAndSourceEntryUrl(run.library.getId(), pagePath)) {
      run.currentPaths.add(attachment.getFilePath());
    }
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
    List<Document> attachments =
        documentRepository.findByLibraryIdAndSourceEntryUrl(run.library.getId(), pagePath);
    for (Document attachment : attachments) {
      vectorChunkStore.deleteByDocumentId(attachment.getId());
      documentRepository.delete(attachment);
      run.events.record(IndexingEventCategory.REMOVED, TRASHED_MESSAGE, attachment.getFilePath());
    }
    if (page.isPresent()) {
      vectorChunkStore.deleteByDocumentId(page.get().getId());
      documentRepository.delete(page.get());
      run.events.record(IndexingEventCategory.REMOVED, TRASHED_MESSAGE, pagePath);
    }
  }

  private void indexAttachments(
      Run run, String pageId, String pagePath, SourceDocumentContext context)
      throws InterruptedException {
    List<ConfluenceAttachment> attachments;
    try {
      attachments = run.client.listAttachments(pageId);
    } catch (ConfluenceAccessException e) {
      // Without the list, this page's attachments would look vanished to the reconciliation.
      run.events.record(
          IndexingEventCategory.UNREACHABLE,
          "Anhänge nicht auflistbar: " + e.getMessage(),
          pagePath);
      run.listingComplete = false;
      return;
    }
    for (ConfluenceAttachment attachment : attachments) {
      String path = attachment.stableUrl();
      run.currentPaths.add(path);
      Optional<Document> existing =
          documentRepository.findByLibraryIdAndFilePath(run.library.getId(), path);
      if (isUnchanged(existing, String.valueOf(attachment.version()))) {
        continue;
      }
      indexAttachment(run, attachment, path, pagePath, context);
    }
  }

  private void indexAttachment(
      Run run,
      ConfluenceAttachment attachment,
      String path,
      String pagePath,
      SourceDocumentContext context)
      throws InterruptedException {
    BoundedDownloader.DownloadedFile downloaded = null;
    try {
      downloaded = run.client.downloadAttachment(attachment);
      String detected = SupportedDocumentFormats.detectMediaType(downloaded.path());
      SupportedDocumentFormats.ContentDecision decision =
          SupportedDocumentFormats.decideForFileName(attachment.fileName(), detected);
      if (!decision.supported()) {
        run.events.record(
            IndexingEventCategory.UNSUPPORTED_FORMAT, "Anhangsformat wird nicht unterstützt", path);
        return;
      }
      if (decision.extensionMismatch()) {
        run.events.record(
            IndexingEventCategory.FORMAT_MISMATCH,
            "Dateiendung passt nicht zum erkannten Inhalt (erkannt: "
                + decision.detectedExtension()
                + ")",
            path);
      }
      FileProcessingResult result =
          fileProcessingService.processUrlFile(
              downloaded.path(),
              attachment.fileName(),
              path,
              String.valueOf(attachment.version()),
              Files.size(downloaded.path()),
              run.library,
              DocumentSourceType.CONFLUENCE,
              pagePath,
              context);
      switch (result) {
        case QUOTA_EXCEEDED ->
            run.events.record(
                IndexingEventCategory.REJECTED,
                storageQuotaService.quotaExceededMessage(run.library.getId()),
                path);
        case NO_EXTRACTABLE_TEXT ->
            run.events.record(
                IndexingEventCategory.REJECTED, DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE, path);
        case FAILED ->
            run.events.record(
                IndexingEventCategory.ERROR, "Verarbeitung des Anhangs fehlgeschlagen", path);
        case PROCESSED -> {
          run.progress.recordDocumentIndexed();
          log.info("Indexed Confluence attachment: {}", path);
        }
        default -> {
          // SKIPPED: unchanged content, deduplicated by processUrlFile itself
        }
      }
    } catch (BoundedDownloader.AttachmentTooLargeException e) {
      run.events.record(
          IndexingEventCategory.REJECTED, "Anhang überschreitet die Größengrenze", path);
    } catch (ConfluenceAccessException e) {
      run.events.record(IndexingEventCategory.UNREACHABLE, e.getMessage(), path);
    } catch (IOException e) {
      log.warn("Failed to process Confluence attachment {}", path, e);
      run.events.record(
          IndexingEventCategory.ERROR, "Verarbeitung des Anhangs fehlgeschlagen", path);
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
