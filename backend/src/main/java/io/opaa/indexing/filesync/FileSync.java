package io.opaa.indexing.filesync;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.common.ByteSizes;
import io.opaa.format.SupportedDocumentFormats;
import io.opaa.indexing.document.DocumentIngest;
import io.opaa.indexing.document.DocumentIngestResult;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.RequestBudgetExhaustedException;
import io.opaa.indexing.maintenance.StaleDocumentCleanupService;
import io.opaa.indexing.source.IndexingRun;
import io.opaa.indexing.source.IndexingRunFailedException;
import io.opaa.indexing.source.ListingOutcome;
import io.opaa.indexing.source.ReconcilingAttachmentAccess;
import io.opaa.indexing.source.SourceFolderMirror;
import io.opaa.indexing.source.SourceFolderPath;
import io.opaa.indexing.source.SourceSyncState;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.sourceaccess.SourceRequestMeter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The file sync of one library over one open {@link FileStore} (ADR-0040, Entscheidung 1; the rules
 * of ADR-0027, Entscheidungen 3 to 5): every container is listed page by page to its last page,
 * every listed entry is marked present whatever its outcome, the change feature decides before any
 * download, and only a listing that reached the last page of every container reports {@link
 * ListingOutcome.Complete}. An unlistable container leaves its bestand alone and is named in
 * protocol and assessment; a spent request budget ends the run truncated; a {@link
 * FileAccessException.RunEnding} fails it with the store's own sentence.
 *
 * <p>Resumption ({@link SourceSyncState}): a run after an interrupted one lists every container
 * again, the unfinished ones first. A store with a {@link ChangeFeed} has its start cursors held
 * back from the first begin of a full sync until it completes. Downloads run {@code
 * downloadConcurrency} at a time while the listing goes on and are ingested on the listing thread
 * in listing order. The sync must be {@link #close() closed} so no download thread or temp file
 * outlives the run.
 */
public final class FileSync implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(FileSync.class);

  public static final String UNLISTABLE_CONTAINER_SUFFIX =
      " Sein Bestand bleibt bis zur nächsten vollständigen Auflistung unverändert.";
  public static final String UNREADABLE_SUFFIX = " Der bereits indizierte Stand bleibt erhalten.";
  public static final String UNSUPPORTED_FORMAT_MESSAGE = "Dateiformat wird nicht unterstützt";
  public static final String GONE_SUFFIX = " Zwischen Auflistung und Abruf entfernt.";
  public static final String RECONCILIATION_FAILED_MESSAGE =
      "Abgleich des Bestands fehlgeschlagen; der nächste Lauf holt ihn nach";
  static final String BUDGET_STALL_ADVICE =
      "Der Lauf hat kein Objekt neu aufgenommen. Budget anheben oder die Geltungsbereiche"
          + " aufteilen.";

  private final IndexingRun frame;
  private final FileStore store;
  private final FileSyncSettings settings;
  private final FileSyncWording wording;
  private final DocumentIngestService documentIngestService;
  private final DocumentRepository documentRepository;
  private final StaleDocumentCleanupService cleanupService;
  private final SourceFolderMirror folderMirror;
  private final SourceSyncState state;
  private final SourceSyncStateRepository syncStateRepository;
  private final Clock clock;
  private final SupportedDocumentFormats supportedFormats;

  /** The containers behind an incomplete listing, in the order met. */
  private final Set<String> unlistedContainerKeys = new LinkedHashSet<>();

  /** Downloads in flight, oldest first; drained on the listing thread in this order. */
  private final Deque<PendingDownload> pending = new ArrayDeque<>();

  /** Temp files a download wrote that the listing thread has not consumed yet. */
  private final Set<Path> landed = ConcurrentHashMap.newKeySet();

  private final AtomicInteger downloadThreads = new AtomicInteger();
  private ExecutorService downloadPool;

  private final Map<String, Duration> containerDurations = new LinkedHashMap<>();
  private final Map<String, Long> notADocumentNotes = new LinkedHashMap<>();
  private final Map<String, Long> deselectedNotes = new LinkedHashMap<>();
  private int total;
  private long listed;
  private long deselected;

  /** One entry fetched off the listing thread, with the folder its ingest places it in. */
  private record PendingDownload(FileEntry entry, UUID folderId, Future<FetchedFile> download) {}

  public FileSync(
      IndexingRun frame,
      FileStore store,
      FileSyncSettings settings,
      FileSyncWording wording,
      DocumentIngestService documentIngestService,
      DocumentRepository documentRepository,
      LibraryFolderService folderService,
      StaleDocumentCleanupService cleanupService,
      SourceSyncState state,
      SourceSyncStateRepository syncStateRepository,
      Clock clock,
      SupportedDocumentFormats supportedFormats) {
    this.frame = frame;
    this.store = store;
    this.settings = settings;
    this.wording = wording;
    this.documentIngestService = documentIngestService;
    this.documentRepository = documentRepository;
    this.cleanupService = cleanupService;
    this.folderMirror = new SourceFolderMirror(folderService, frame.library());
    this.state = state;
    this.syncStateRepository = syncStateRepository;
    this.clock = clock;
    this.supportedFormats = supportedFormats;
  }

  /** The full sync over every container of the store. */
  public ListingOutcome run() throws InterruptedException {
    List<FileContainer> containers = store.containers();
    List<FileContainer> ordered = orderForResumption(containers, state);
    boolean resumed = state.isFullSyncInterrupted();
    state.beginFullSync(frame.jobId());
    holdStartCursors(containers, resumed);
    SourceSyncState saved = syncStateRepository.save(state);
    // the state holds every container listed completely so far - the next run starts with the rest
    frame.budgetContinuation(this::fullSyncContinuation);
    frame.budgetStallAdvice(BUDGET_STALL_ADVICE);
    try {
      for (FileContainer container : ordered) {
        Instant start = clock.instant();
        boolean listedCompletely = listContainer(container);
        drainAll();
        containerDurations.put(container.key(), Duration.between(start, clock.instant()));
        if (listedCompletely) {
          saved.markScopeCompleted(container.key());
          saved = syncStateRepository.save(saved);
        }
      }
    } finally {
      // the figures belong to a failed run as well - they are the diagnosis of "too many entries"
      recordSummaries();
    }
    if (!unlistedContainerKeys.isEmpty()) {
      log.info(
          "File sync for library {} listed incompletely ({}) - keeping the bestand, no"
              + " reconciliation",
          frame.library().getId(),
          unlistedContainerKeys);
      return ListingOutcome.incomplete(List.copyOf(unlistedContainerKeys));
    }
    // Folders are pruned after the document cleanup of a complete listing, so a folder emptied by
    // it goes in the same run (ADR-0020); without the reconciliation the full sync stays open.
    SourceSyncState completedState = saved;
    frame.afterReconciliation(
        reconciled -> {
          folderMirror.prune();
          if (reconciled) {
            completedState.completeFullSync(clock.instant());
            syncStateRepository.save(completedState);
          } else {
            frame
                .events()
                .recordRunNote(IndexingEventCategory.ERROR, RECONCILIATION_FAILED_MESSAGE);
          }
        });
    return ListingOutcome.complete();
  }

  /**
   * The event run: one {@link FileStore#head} per reported file, then the full sync's own visit for
   * a present file and a removal with attachments for a {@link FileAccessException.Gone}. No
   * listing, no state, and {@link ListingOutcome#partial()}, so the frame reconciles nothing.
   *
   * @param outside reported references outside every container, counted as skipped
   * @param dropped references the connector dropped before, noted once
   */
  public ListingOutcome refresh(List<FileReference> references, int outside, int dropped)
      throws InterruptedException {
    frame.progress().setTotal(references.size() + outside);
    frame.progress().report();
    // no next event run continues this batch: the scheduled run covers the rest
    frame.budgetContinuation(
        () -> "die übrigen gemeldeten Objekte nimmt der nächste geplante Lauf auf");
    try {
      for (int i = 0; i < outside; i++) {
        frame.progress().recordSkipped();
        frame.progress().report();
      }
      for (FileReference reference : references) {
        checkReported(reference);
        frame.progress().report();
      }
      drainAll();
    } finally {
      if (dropped > 0) {
        frame
            .events()
            .recordRunNote(
                IndexingEventCategory.REJECTED, dropped + wording.droppedReferencesNote());
      }
      recordSummaries();
    }
    return ListingOutcome.partial();
  }

  private void checkReported(FileReference reference) throws InterruptedException {
    FileEntry entry;
    try {
      entry = store.head(reference.container(), reference.id());
    } catch (FileAccessException.Gone gone) {
      // the positive finding a deletion needs
      removeGone(reference.filePath());
      return;
    } catch (FileAccessException e) {
      handleFailure(reference.filePath(), e);
      return;
    }
    if (entry.exclusion() instanceof Exclusion.Unavailable unavailable) {
      frame.markPresent(entry.filePath());
      skip(IndexingEventCategory.REJECTED, unavailable.message(), entry.filePath());
      return;
    }
    listed++;
    visit(entry);
  }

  /** Removes the document under {@code filePath} with every attachment below it, deepest first. */
  private void removeGone(String filePath) {
    Optional<Document> document =
        documentRepository.findByLibraryIdAndFilePath(frame.library().getId(), filePath);
    if (document.isEmpty()) {
      frame.progress().recordSkipped();
      return;
    }
    cleanupService.removeWithAttachments(document.get(), frame.events(), wording.goneConfirmed());
    frame.markAbsent(filePath);
    frame.progress().recordSkipped();
  }

  /** Unfinished containers of an interrupted full sync first, then the already completed ones. */
  static List<FileContainer> orderForResumption(
      List<FileContainer> containers, SourceSyncState state) {
    Set<String> completed = state.isFullSyncInterrupted() ? state.completedScopeKeys() : Set.of();
    List<FileContainer> ordered = new ArrayList<>();
    for (FileContainer container : containers) {
      if (!completed.contains(container.key())) {
        ordered.add(container);
      }
    }
    for (FileContainer container : containers) {
      if (completed.contains(container.key())) {
        ordered.add(container);
      }
    }
    return ordered;
  }

  /**
   * Holds one start cursor per stream before anything is listed - on the first begin of a full sync
   * only; a resumed one keeps the cursors its first begin held, so no change in an already
   * completed container is lost between the abort and the resumption.
   */
  private void holdStartCursors(List<FileContainer> containers, boolean resumed)
      throws InterruptedException {
    Optional<ChangeFeed> feed = store.changes();
    if (feed.isEmpty() || (resumed && !state.pendingChangeCursors().isEmpty())) {
      return;
    }
    Map<String, String> cursors = new LinkedHashMap<>();
    try {
      for (FileContainer container : containers) {
        String feedKey = feed.get().feedKey(container);
        if (!cursors.containsKey(feedKey)) {
          cursors.put(feedKey, feed.get().startCursor(feedKey));
        }
      }
    } catch (FileAccessException e) {
      throw new IndexingRunFailedException(e.getMessage(), e);
    }
    state.holdPendingChangeCursors(cursors);
  }

  /**
   * Abandons every download in flight, waits briefly for the download threads to end and deletes
   * every temp file a download wrote that the listing thread never consumed.
   */
  @Override
  public void close() {
    for (PendingDownload item : pending) {
      item.download().cancel(true);
    }
    pending.clear();
    if (downloadPool != null) {
      downloadPool.shutdownNow();
      try {
        if (!downloadPool.awaitTermination(10, TimeUnit.SECONDS)) {
          log.warn(
              "Download threads of library {} did not end within 10 s", frame.library().getId());
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    for (Path file : landed) {
      deleteQuietly(file);
    }
    landed.clear();
  }

  /**
   * @return whether the container was listed to its last page - {@code false} for one the store
   *     cannot list, which stays out of the state and is listed first next time
   */
  private boolean listContainer(FileContainer container) throws InterruptedException {
    String continuation = null;
    do {
      FilePage page;
      try {
        page = store.list(container, continuation);
      } catch (FileAccessException.ContainerUnlistable e) {
        // no deletion finding: the run says so, the rest is still processed, nothing reconciled
        log.warn(
            "Container {} not listable for library {}: {}",
            container.key(),
            frame.library().getId(),
            e.getMessage());
        frame
            .events()
            .record(
                IndexingEventCategory.REJECTED,
                "Geltungsbereich „"
                    + container.key()
                    + "“: "
                    + e.getMessage()
                    + UNLISTABLE_CONTAINER_SUFFIX,
                container.key());
        unlistedContainerKeys.add(container.key());
        return false;
      } catch (FileAccessException e) {
        throw new IndexingRunFailedException(e.getMessage(), e);
      }
      listed += page.entries().size();
      if (listed > settings.maxEntriesPerRun()) {
        throw new IndexingRunFailedException(wording.tooManyEntries(settings.maxEntriesPerRun()));
      }
      List<FileEntry> admitted = new ArrayList<>();
      for (FileEntry entry : page.entries()) {
        if (entry.exclusion() instanceof Exclusion.Deselected deselection) {
          deselected++;
          deselectedNotes.merge(deselection.note(), 1L, Long::sum);
        } else {
          admitted.add(entry);
        }
      }
      total += admitted.size();
      frame.progress().setTotal(total);
      frame.progress().report();
      for (String subtree : page.unchangedSubtrees()) {
        keepUnchangedSubtree(subtree);
      }
      for (FileEntry entry : admitted) {
        visit(entry);
      }
      continuation = page.next();
    } while (continuation != null);
    return true;
  }

  /** Every stored row below {@code prefix} stays present and keeps its folder. */
  private void keepUnchangedSubtree(String prefix) {
    for (Document document :
        documentRepository.findByLibraryIdAndFilePathStartingWith(
            frame.library().getId(), prefix)) {
      frame.markPresent(document.getFilePath());
      folderMirror.markSeen(document.getFolderId());
    }
  }

  /**
   * One listed entry: present from the first look, then skipped for what the listing already shows
   * (no document, an unsupported extension, unavailable, oversize), skipped without a download when
   * the change feature is already stored, otherwise fetched and handed to the document path. A name
   * without an extension costs one {@link FileStore#head} whose media type decides.
   */
  private void visit(FileEntry entry) throws InterruptedException {
    String filePath = entry.filePath();
    frame.markPresent(filePath);
    if (entry.exclusion() instanceof Exclusion.NotADocument notADocument) {
      // one note per run instead of one entry per marker: the protocol holds 500 entries
      notADocumentNotes.merge(notADocument.note(), 1L, Long::sum);
      frame.progress().recordSkipped();
      return;
    }
    String fileName = entry.fileName();
    boolean supportedByName = supportedFormats.isSupported(fileName);
    if (!supportedByName && hasExtension(fileName)) {
      // the mass case of a file store (images, archives): no row, no lookup, no folder
      skip(IndexingEventCategory.UNSUPPORTED_FORMAT, UNSUPPORTED_FORMAT_MESSAGE, filePath);
      return;
    }
    // A stored row keeps (or receives) its folder whatever this run does with the entry.
    Optional<Document> existing =
        documentRepository.findByLibraryIdAndFilePath(frame.library().getId(), filePath);
    UUID folderId = existing.isPresent() ? folderFor(entry) : null;
    existing.ifPresent(document -> mirrorFolder(document, folderId));
    if (entry.exclusion() instanceof Exclusion.Unavailable unavailable) {
      skip(IndexingEventCategory.REJECTED, unavailable.message(), filePath);
      return;
    }
    if (entry.size() > settings.maxFileSizeBytes()) {
      skip(
          IndexingEventCategory.REJECTED,
          wording.tooLarge(entry, settings.maxFileSizeBytes()),
          filePath);
      return;
    }
    String marker = entry.changeMarker();
    if (marker != null && existing.filter(document -> document.isUnchangedAt(marker)).isPresent()) {
      log.debug("Skipping unchanged file: {}", filePath);
      frame.progress().recordSkipped();
      return;
    }
    if (!supportedByName && !headAdmits(entry)) {
      return;
    }
    enqueueDownload(entry, existing.isPresent() ? folderId : folderFor(entry));
  }

  /**
   * Fetches the entry off the listing thread when downloads may run concurrently, serially
   * otherwise; with {@code downloadConcurrency} downloads in flight the oldest is ingested first.
   * An entry skipped without a download is noted the moment it is met, so its protocol entry may
   * precede that of an earlier one still downloading.
   */
  private void enqueueDownload(FileEntry entry, UUID folderId) throws InterruptedException {
    int concurrency = settings.downloadConcurrency();
    if (concurrency <= 1) {
      FetchedFile fetched = fetch(entry);
      if (fetched != null) {
        ingest(entry, folderId, fetched);
      }
      return;
    }
    while (pending.size() >= concurrency) {
      drainOne();
    }
    Future<FetchedFile> future =
        downloadPool()
            .submit(
                () -> {
                  FetchedFile fetched = store.fetch(entry, settings.maxFileSizeBytes());
                  landed.add(fetched.file());
                  return fetched;
                });
    pending.add(new PendingDownload(entry, folderId, future));
  }

  private ExecutorService downloadPool() {
    if (downloadPool == null) {
      String library = frame.library().getId().toString();
      downloadPool =
          Executors.newFixedThreadPool(
              settings.downloadConcurrency(),
              task -> {
                Thread thread =
                    new Thread(
                        task,
                        settings.downloadThreadPrefix()
                            + library
                            + "-"
                            + downloadThreads.incrementAndGet());
                thread.setDaemon(true);
                return thread;
              });
    }
    return downloadPool;
  }

  /**
   * Ingests the oldest download in flight, waiting for it if needed. A budget spent on the download
   * thread ends the run like one spent on the listing thread.
   */
  private void drainOne() throws InterruptedException {
    PendingDownload item = pending.poll();
    if (item == null) {
      return;
    }
    FetchedFile fetched;
    try {
      fetched = item.download().get();
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RequestBudgetExhaustedException exhausted) {
        throw exhausted;
      }
      if (e.getCause() instanceof FileAccessException failure) {
        handleFailure(item.entry().filePath(), failure);
      } else {
        frame.recordFailure(item.entry().filePath(), e.getCause() == null ? e : e.getCause());
      }
      return;
    }
    ingest(item.entry(), item.folderId(), fetched);
  }

  private void drainAll() throws InterruptedException {
    while (!pending.isEmpty()) {
      drainOne();
    }
  }

  /**
   * The folder the entry's chain maps to, materialised through the run's mirror but not yet pinned
   * against pruning - only a row pins its folder. A chain no folder row can carry leaves the entry
   * at the root, a cut chain ends in the deepest allowed folder, a failing folder layer leaves the
   * entry at the root - each with a warning, never at the document's expense.
   */
  private UUID folderFor(FileEntry entry) {
    SourceFolderPath path = entry.folder();
    if (path.rejected()) {
      log.warn(
          "Cannot map segment \"{}\" of {} to a folder name - leaving the document at the"
              + " library root",
          path.rejectedSegment(),
          entry.filePath());
    } else if (path.truncated()) {
      log.warn(
          "{} nests deeper than {} folders - placing the document in the deepest allowed one",
          entry.filePath(),
          SourceFolderPath.MAX_DEPTH);
    }
    try {
      return folderMirror.folderFor(path.segments());
    } catch (Exception e) {
      log.warn(
          "Failed to mirror the source folder of {} - leaving it at the root", entry.filePath(), e);
      return null;
    }
  }

  /**
   * Places an existing row in {@code folderId} and pins the folder; its attachments follow only
   * when the row actually moved.
   */
  private void mirrorFolder(Document document, UUID folderId) {
    try {
      folderMirror.markSeen(folderId);
      if (!Objects.equals(document.getFolderId(), folderId)) {
        applyFolder(document, folderId);
      }
    } catch (Exception e) {
      log.warn("Failed to mirror the source folder of {}", document.getFilePath(), e);
    }
  }

  private void applyFolder(Document document, UUID folderId) {
    if (!Objects.equals(document.getFolderId(), folderId)) {
      document.setFolderId(folderId);
      documentRepository.save(document);
    }
    for (Document child : documentRepository.findByParentDocumentId(document.getId())) {
      applyFolder(child, folderId);
    }
  }

  /** The {@link FileStore#head} of an extension-less name: admitted when its media type is. */
  private boolean headAdmits(FileEntry entry) throws InterruptedException {
    FileEntry head;
    try {
      head = store.head(entry.container(), entry.id());
    } catch (FileAccessException e) {
      return handleFailure(entry.filePath(), e);
    }
    if (head.exclusion() instanceof Exclusion.Unavailable unavailable) {
      skip(IndexingEventCategory.REJECTED, unavailable.message(), entry.filePath());
      return false;
    }
    if (supportedFormats.extensionForContentType(head.mediaType()) == null) {
      skip(
          IndexingEventCategory.UNSUPPORTED_FORMAT,
          UNSUPPORTED_FORMAT_MESSAGE
              + " (Content-Type "
              + (head.mediaType() == null ? "unbekannt" : head.mediaType())
              + ")",
          entry.filePath());
      return false;
    }
    return true;
  }

  /** The entry's bytes in a temp file, or {@code null} once the failure was handled. */
  private FetchedFile fetch(FileEntry entry) throws InterruptedException {
    try {
      return store.fetch(entry, settings.maxFileSizeBytes());
    } catch (FileAccessException e) {
      handleFailure(entry.filePath(), e);
      return null;
    }
  }

  private void ingest(FileEntry entry, UUID folderId, FetchedFile fetched)
      throws InterruptedException {
    Path file = fetched.file();
    String filePath = entry.filePath();
    try {
      String changeMarker =
          entry.changeMarker() != null ? entry.changeMarker() : fetched.changeMarker();
      ReconcilingAttachmentAccess attachmentAccess = frame.attachmentAccess(entry.context());
      DocumentIngestResult result =
          documentIngestService.ingest(
              DocumentIngest.builder(frame.library())
                  .file(file, fetched.size())
                  .filePath(filePath)
                  .fileName(entry.fileName())
                  .sourceType(frame.sourceType())
                  .context(entry.context())
                  .changeMarker(changeMarker)
                  .folder(folderId)
                  .build(),
              attachmentAccess);
      if (frame.recordOutcome(result, filePath)) {
        frame.markReprocessed(filePath);
        log.info("Indexed {} file: {}", frame.sourceType(), filePath);
        // the row pins its folder now; attachments a re-parse enumerated are new rows without one
        folderMirror.markSeen(folderId);
        documentRepository
            .findByLibraryIdAndFilePath(frame.library().getId(), filePath)
            .ifPresent(document -> applyFolder(document, folderId));
      } else if (result == DocumentIngestResult.SKIPPED) {
        // a new feature over the same bytes: the row keeps its id and chunks
        log.info("{} changed its feature but not its checksum, provenance refreshed", filePath);
      }
    } catch (Exception e) {
      IndexingRun.rethrowRunEnding(e);
      frame.recordFailure(filePath, e);
    } finally {
      landed.remove(file);
      deleteQuietly(file);
      frame.progress().report();
    }
  }

  private static void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      log.warn("Failed to delete temp file: {}", file, e);
    }
  }

  /**
   * What one entry's failed check or download means: a confirmed absence leaves the reconciliation
   * set, a refused read or an unavailable or oversize file keeps the stored version, a {@link
   * FileAccessException.RunEnding} fails the run, and anything else counts as failed while the run
   * goes on.
   *
   * @return always {@code false}: the entry is not admitted to a download
   */
  private boolean handleFailure(String filePath, FileAccessException e) {
    switch (e) {
      case FileAccessException.Gone gone -> {
        frame.markAbsent(filePath);
        skip(IndexingEventCategory.REJECTED, gone.getMessage() + GONE_SUFFIX, filePath);
      }
      case FileAccessException.Unreadable unreadable ->
          skip(
              IndexingEventCategory.REJECTED,
              unreadable.getMessage() + UNREADABLE_SUFFIX,
              filePath);
      case FileAccessException.Unavailable unavailable ->
          skip(IndexingEventCategory.REJECTED, unavailable.getMessage(), filePath);
      case FileAccessException.TooLarge tooLarge ->
          skip(IndexingEventCategory.REJECTED, tooLarge.getMessage(), filePath);
      case FileAccessException.RunEnding runEnding ->
          throw new IndexingRunFailedException(runEnding.getMessage(), runEnding);
      default -> {
        frame.events().record(IndexingEventCategory.UNREACHABLE, e.getMessage(), filePath);
        frame.progress().recordFailed();
      }
    }
    return false;
  }

  private void skip(IndexingEventCategory category, String message, String filePath) {
    frame.events().record(category, message, filePath);
    frame.progress().recordSkipped();
  }

  /** One note per run for what was skipped in bulk - the protocol holds 500 entries. */
  private void recordSummaries() {
    notADocumentNotes.forEach(
        (note, count) ->
            frame.events().recordRunNote(IndexingEventCategory.UNSUPPORTED_FORMAT, count + note));
    deselectedNotes.forEach(
        (note, count) ->
            frame.events().recordRunNote(IndexingEventCategory.REJECTED, count + note));
    frame.events().recordRunNote(IndexingEventCategory.SUMMARY, summaryMessage());
  }

  private boolean eventRun() {
    return frame.runMode() == IndexingRunMode.EVENT;
  }

  /** The run's figures in one German sentence - what an operator reads throughput against. */
  private String summaryMessage() {
    SourceRequestMeter meter = store.meter();
    long checked =
        frame.progress().processedCount()
            + frame.progress().skippedCount()
            + frame.progress().failedCount();
    StringBuilder message =
        new StringBuilder()
            .append(meter.requests())
            .append(" Anfragen, ")
            .append(ByteSizes.format(meter.bytesDownloaded()))
            .append(" geladen; ")
            .append(eventRun() ? checked : listed)
            .append(eventRun() ? " gemeldete Objekte geprüft, " : " Objekte gelistet, ")
            .append(eventRun() ? "" : deselected + " durch Muster ausgeschlossen, ")
            .append(frame.progress().skippedCount())
            .append(" übersprungen, ")
            .append(frame.progress().processedCount())
            .append(" neu verarbeitet, ")
            .append(frame.progress().failedCount())
            .append(" fehlgeschlagen");
    if (!containerDurations.isEmpty()) {
      message.append("; Dauer je Geltungsbereich: ");
      List<String> parts = new ArrayList<>();
      containerDurations.forEach(
          (key, duration) -> parts.add(key + " " + Math.max(0, duration.toSeconds()) + " s"));
      message.append(String.join(", ", parts));
    }
    return message.toString();
  }

  /** Where the next full sync continues once this one's budget is spent, for the frame's note. */
  private String fullSyncContinuation() {
    return "der Lauf endet unvollständig, der nächste Lauf listet alle Geltungsbereiche erneut und"
        + " lädt nur, was noch fehlt"
        + (unlistedContainerKeys.isEmpty()
            ? ""
            : "; bis dahin nicht auflistbar: " + String.join(", ", unlistedContainerKeys));
  }

  private static boolean hasExtension(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot > 0 && dot < fileName.length() - 1;
  }
}
