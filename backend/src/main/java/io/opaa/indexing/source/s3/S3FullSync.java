package io.opaa.indexing.source.s3;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.common.ByteSizes;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentIngest;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.IndexingEventCategory;
import io.opaa.indexing.SourceDocumentContext;
import io.opaa.indexing.StaleDocumentCleanupService;
import io.opaa.indexing.SupportedDocumentFormats;
import io.opaa.indexing.source.IndexingRun;
import io.opaa.indexing.source.IndexingRunFailedException;
import io.opaa.indexing.source.ListingOutcome;
import io.opaa.indexing.source.ReconcilingAttachmentAccess;
import io.opaa.indexing.source.RequestBudgetExhaustedException;
import io.opaa.indexing.source.SourceFolderMirror;
import io.opaa.indexing.source.SourceFolderPath;
import io.opaa.library.LibraryFolderService;
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
 * One full sync of an S3 library over one open store (ADR-0027, Entscheidung 3): every scope is
 * listed page by page to its last page, every listed object that the patterns admit is marked
 * present whatever its outcome, the change feature (Entscheidung 4) decides before any download,
 * and only a listing that reached the last page of every scope reports {@link
 * ListingOutcome.Complete}. A scope the credentials cannot list leaves its bestand alone and is
 * named in the protocol and the assessment; a spent request budget ends the run truncated through
 * the frame, which notes where the next run continues; a store-wide failure (credentials, clock,
 * TLS, reachability) fails the run with the access layer's own sentence.
 *
 * <p>Resumption ({@link S3SyncState}): a run after an interrupted one lists every scope again - the
 * scopes the interrupted run did not finish first - and saves only the downloads, since an object
 * stored at its listed feature costs no call. Downloads run {@code downloadConcurrency} at a time
 * on their own threads while the listing goes on; every download is handed to the document path on
 * the listing thread in listing order, so counters, folders, repository writes and the entries of
 * downloaded objects stay sequential. The sync must be {@link #close() closed} so no download
 * thread or temp file outlives the run.
 */
final class S3FullSync implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(S3FullSync.class);

  static final String UNLISTABLE_SCOPE_SUFFIX =
      " Sein Bestand bleibt bis zur nächsten vollständigen Auflistung unverändert.";
  static final String UNREADABLE_OBJECT_SUFFIX = " Der bereits indizierte Stand bleibt erhalten.";
  static final String UNSUPPORTED_FORMAT_MESSAGE = "Dateiformat wird nicht unterstützt";
  static final String GONE_SUFFIX = " Zwischen Auflistung und Abruf entfernt.";
  static final String FOLDER_MARKERS_SUFFIX =
      " Ordnermarker (Schlüssel endet auf „/“) übersprungen; sie sind keine Dokumente";
  static final String EXCLUDED_KEYS_SUFFIX =
      " Schlüssel durch die Ein-/Ausschlussmuster ausgeschlossen; sie sind nicht Teil des Bestands"
          + " und werden entfernt, falls ein früherer Lauf sie aufgenommen hat";
  static final String RECONCILIATION_FAILED_MESSAGE =
      "Abgleich des Bestands fehlgeschlagen; der nächste Lauf holt ihn nach";
  static final String BUDGET_STALL_ADVICE =
      "Der Lauf hat kein Objekt neu aufgenommen. Budget anheben oder die Geltungsbereiche"
          + " aufteilen.";

  private final IndexingRun frame;
  private final S3ObjectStore store;
  private final S3Properties properties;
  private final FileProcessingService fileProcessingService;
  private final DocumentRepository documentRepository;
  private final StaleDocumentCleanupService cleanupService;
  private final List<S3Scope> scopes;
  private final S3KeyPatterns patterns;
  private final SourceFolderMirror folderMirror;

  /** Whether bucket and prefix segments open every folder chain - a library with several scopes. */
  private final boolean scopeRootChain;

  /** The scopes behind an incomplete listing as {@code bucket/prefix}, in the order met. */
  private final Set<String> unlistableScopeKeys = new LinkedHashSet<>();

  private final S3SyncState state;
  private final S3SyncStateRepository syncStateRepository;
  private final Clock clock;

  /** Downloads in flight, oldest first; drained on the listing thread in this order. */
  private final Deque<PendingDownload> pending = new ArrayDeque<>();

  /**
   * Temp files a download thread wrote and the listing thread has not consumed yet - what {@link
   * #close()} sweeps, independent of whether the future still hands its result over.
   */
  private final Set<Path> landed = ConcurrentHashMap.newKeySet();

  private final AtomicInteger downloadThreads = new AtomicInteger();
  private ExecutorService downloadPool;

  private final Map<String, Duration> scopeDurations = new LinkedHashMap<>();
  private int total;
  private long listed;
  private long folderMarkers;
  private long excludedKeys;

  /** One object fetched off the listing thread, with everything its ingest needs. */
  private record PendingDownload(
      S3Scope scope,
      S3ObjectSummary object,
      String filePath,
      String fileName,
      String marker,
      UUID folderId,
      Future<S3Download> download) {}

  S3FullSync(
      IndexingRun frame,
      S3ObjectStore store,
      S3SourceSettings settings,
      S3Properties properties,
      FileProcessingService fileProcessingService,
      DocumentRepository documentRepository,
      LibraryFolderService folderService,
      StaleDocumentCleanupService cleanupService,
      S3SyncState state,
      S3SyncStateRepository syncStateRepository,
      Clock clock) {
    this.frame = frame;
    this.store = store;
    this.properties = properties;
    this.fileProcessingService = fileProcessingService;
    this.documentRepository = documentRepository;
    this.cleanupService = cleanupService;
    this.scopes = settings.scopes();
    this.patterns = S3KeyPatterns.of(settings);
    this.folderMirror = new SourceFolderMirror(folderService, frame.library());
    this.scopeRootChain = settings.scopes().size() > 1;
    this.state = state;
    this.syncStateRepository = syncStateRepository;
    this.clock = clock;
  }

  ListingOutcome run(List<S3Scope> scopes) throws InterruptedException {
    List<S3Scope> ordered = orderForResumption(scopes, state);
    state.beginFullSync(frame.jobId());
    S3SyncState saved = syncStateRepository.save(state);
    // the state holds every scope listed completely so far - the next run starts with the rest
    frame.budgetContinuation(this::fullSyncContinuation);
    frame.budgetStallAdvice(BUDGET_STALL_ADVICE);
    try {
      for (S3Scope scope : ordered) {
        Instant scopeStart = clock.instant();
        boolean listedCompletely = listScope(scope);
        drainAll();
        scopeDurations.put(scope.key(), Duration.between(scopeStart, clock.instant()));
        if (listedCompletely) {
          saved.markScopeCompleted(scope.key());
          saved = syncStateRepository.save(saved);
        }
      }
    } finally {
      // the figures belong to a failed run as well - they are the diagnosis of "too many objects"
      recordSummaries();
    }
    if (!unlistableScopeKeys.isEmpty()) {
      log.info(
          "S3 full sync for library {} listed incompletely ({}) - keeping the bestand, no"
              + " reconciliation",
          frame.library().getId(),
          unlistableScopeKeys);
      return ListingOutcome.incomplete(List.copyOf(unlistableScopeKeys));
    }
    // Folders are pruned only after the document cleanup of a complete listing, so a folder
    // emptied by that cleanup goes in the same run (ADR-0020, like FILESYSTEM and HTTP_DIRECTORY);
    // and without the reconciliation the full sync is not complete - the state stays open, so the
    // next run reconciles again. The frame holds one hook, so both compose here.
    S3SyncState completedState = saved;
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

  static final String GONE_CONFIRMED_MESSAGE =
      "Vom Objektspeicher als gelöscht bestätigt, entfernt";
  static final String DROPPED_EVENTS_SUFFIX =
      " gemeldete Objekte liegen außerhalb der Geltungsbereiche oder Muster und wurden verworfen";

  /**
   * The event run (ADR-0027, Entscheidung 6): one {@code HeadObject} per reported {@code
   * bucket/key}, then the full sync's own object visit for a present object and a removal with
   * attachments for a {@code 404} - the store's answer is the finding, the notification only said
   * where to look. No listing, no state, and {@link ListingOutcome#partial()} at the end, so the
   * frame reconciles nothing.
   */
  ListingOutcome refresh(Set<String> references, int dropped) throws InterruptedException {
    frame.progress().setTotal(references.size());
    frame.progress().report();
    // no next event run continues this batch: the scheduled run covers the rest
    frame.budgetContinuation(
        () -> "die übrigen gemeldeten Objekte nimmt der nächste geplante Lauf auf");
    try {
      for (String reference : references.stream().sorted().toList()) {
        checkReported(reference);
        frame.progress().report();
      }
      drainAll();
    } finally {
      if (dropped > 0) {
        frame
            .events()
            .recordRunNote(IndexingEventCategory.REJECTED, dropped + DROPPED_EVENTS_SUFFIX);
      }
      recordSummaries();
    }
    return ListingOutcome.partial();
  }

  private void checkReported(String reference) throws InterruptedException {
    int slash = reference.indexOf('/');
    String bucket = slash < 0 ? reference : reference.substring(0, slash);
    String key = slash < 0 ? "" : reference.substring(slash + 1);
    S3Scope scope =
        scopes.stream()
            .filter(s -> s.bucket().equals(bucket) && s.contains(key))
            .findFirst()
            .orElse(null);
    if (scope == null || key.isEmpty()) {
      frame.progress().recordSkipped();
      return;
    }
    String filePath = filePath(bucket, key);
    S3ObjectHead head;
    try {
      head = store.headObject(bucket, key);
    } catch (S3AccessException.ObjectNotFound gone) {
      // the positive finding a deletion needs (Entscheidung 3)
      removeGone(filePath);
      return;
    } catch (S3AccessException e) {
      handleObjectFailure(filePath, e);
      return;
    }
    if (head.archived()) {
      frame.markPresent(filePath);
      skip(
          IndexingEventCategory.REJECTED, archivedMessage(filePath, head.storageClass()), filePath);
      return;
    }
    listed++;
    // the head already judged the archive state (a completed restore reads normally), so the
    // summary carries the class only when the object really is unreadable
    visitObject(
        scope,
        new S3ObjectSummary(
            key,
            head.eTag(),
            head.size(),
            head.lastModified(),
            head.archived() ? head.storageClass() : null));
  }

  /** Removes the document under {@code filePath} with every attachment below it, deepest first. */
  private void removeGone(String filePath) {
    Optional<Document> document =
        documentRepository.findByLibraryIdAndFilePath(frame.library().getId(), filePath);
    if (document.isEmpty()) {
      frame.progress().recordSkipped();
      return;
    }
    cleanupService.removeWithAttachments(document.get(), frame.events(), GONE_CONFIRMED_MESSAGE);
    frame.markAbsent(filePath);
    frame.progress().recordSkipped();
  }

  /** Unfinished scopes of an interrupted full sync first, then the already completed ones. */
  static List<S3Scope> orderForResumption(List<S3Scope> scopes, S3SyncState state) {
    Set<String> completed = state.isFullSyncInterrupted() ? state.completedScopeKeys() : Set.of();
    List<S3Scope> ordered = new ArrayList<>();
    for (S3Scope scope : scopes) {
      if (!completed.contains(scope.key())) {
        ordered.add(scope);
      }
    }
    for (S3Scope scope : scopes) {
      if (completed.contains(scope.key())) {
        ordered.add(scope);
      }
    }
    return ordered;
  }

  /**
   * Abandons every download still in flight, waits briefly for the download threads to end and
   * deletes every temp file a download wrote that the listing thread never consumed - a cancelled
   * task may have finished its transfer before the cancellation reached it.
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
              "S3 download threads of library {} did not end within 10 s", frame.library().getId());
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
   * @return whether the scope was listed to its last page - {@code false} for a scope the
   *     credentials cannot list, which stays out of the state and is listed first next time
   */
  private boolean listScope(S3Scope scope) throws InterruptedException {
    String token = null;
    do {
      S3ListPage page;
      try {
        page = store.listObjects(scope, token);
      } catch (S3AccessException.ListForbidden
          | S3AccessException.BucketNotFound
          | S3AccessException.ListingIncomplete e) {
        // ADR-0027, Entscheidung 3: a scope that cannot be listed is no deletion finding - the
        // run says so, the rest of the selection is still processed, nothing is reconciled. A
        // store that keeps throttling or is unreachable fails the run instead (the catch below).
        log.warn(
            "S3 scope {} not listable for library {}: {}",
            scope.key(),
            frame.library().getId(),
            e.getMessage());
        frame
            .events()
            .record(
                IndexingEventCategory.REJECTED,
                "Geltungsbereich „"
                    + scope.key()
                    + "“: "
                    + e.getMessage()
                    + UNLISTABLE_SCOPE_SUFFIX,
                scope.key());
        unlistableScopeKeys.add(scope.key());
        return false;
      } catch (S3AccessException e) {
        throw new IndexingRunFailedException(e.getMessage(), e);
      }
      listed += page.objects().size();
      if (listed > properties.maxObjectsPerRun()) {
        throw new IndexingRunFailedException(tooManyObjectsMessage());
      }
      List<S3ObjectSummary> admitted = new ArrayList<>();
      for (S3ObjectSummary object : page.objects()) {
        if (patterns.admits(object.key())) {
          admitted.add(object);
        } else {
          excludedKeys++;
        }
      }
      total += admitted.size();
      frame.progress().setTotal(total);
      frame.progress().report();
      for (S3ObjectSummary object : admitted) {
        visitObject(scope, object);
      }
      token = page.nextContinuationToken();
    } while (token != null);
    return true;
  }

  /**
   * One listed object: present from the first look, then skipped for what the listing already shows
   * (a folder marker, an archive class, an oversize, an unsupported extension), skipped without a
   * download when the change feature is already stored, otherwise fetched and handed to the
   * document path. An extension-less key costs one {@code HeadObject} whose content type decides
   * (Entscheidung 5).
   */
  private void visitObject(S3Scope scope, S3ObjectSummary object) throws InterruptedException {
    String bucket = scope.bucket();
    String key = object.key();
    String filePath = filePath(bucket, key);
    frame.markPresent(filePath);
    String fileName = object.fileName();
    if (object.isFolderMarker() || fileName.isEmpty()) {
      // one summary note per run instead of one entry per marker: a console-created bucket
      // carries a marker per folder, and the protocol holds 500 entries
      folderMarkers++;
      frame.progress().recordSkipped();
      return;
    }
    boolean supportedByName = SupportedDocumentFormats.isSupported(fileName);
    if (!supportedByName && hasExtension(fileName)) {
      // the mass case of an object store (images, archives): no row, no lookup, no folder
      skip(IndexingEventCategory.UNSUPPORTED_FORMAT, UNSUPPORTED_FORMAT_MESSAGE, filePath);
      return;
    }
    // A row an earlier run stored keeps (or receives) its folder whatever this run does with the
    // object - an archived or oversize object with a row still has a place in the structure.
    Optional<Document> existing =
        documentRepository.findByLibraryIdAndFilePath(frame.library().getId(), filePath);
    UUID folderId = existing.isPresent() ? folderFor(scope, key, filePath) : null;
    existing.ifPresent(document -> mirrorFolder(document, folderId));
    if (object.isArchived()) {
      skip(
          IndexingEventCategory.REJECTED,
          archivedMessage(filePath, object.storageClass()),
          filePath);
      return;
    }
    if (object.size() > properties.maxObjectSizeBytes()) {
      skip(
          IndexingEventCategory.REJECTED,
          S3AccessException.ObjectTooLarge.describe(bucket, key, properties.maxObjectSizeBytes()),
          filePath);
      return;
    }
    String marker = S3ChangeMarker.of(object);
    if (marker != null && existing.filter(document -> document.isUnchangedAt(marker)).isPresent()) {
      log.debug("Skipping unchanged S3 object: {}", filePath);
      frame.progress().recordSkipped();
      return;
    }
    if (!supportedByName && !headAdmits(bucket, key, filePath)) {
      return;
    }
    enqueueDownload(
        scope,
        object,
        filePath,
        fileName,
        marker,
        existing.isPresent() ? folderId : folderFor(scope, key, filePath));
  }

  /**
   * Fetches the object off the listing thread when downloads may run concurrently, serially
   * otherwise; with {@code downloadConcurrency} downloads in flight the oldest is ingested first,
   * so the queue never grows past that bound and downloads are ingested among themselves in listing
   * order. An object skipped without a download is noted the moment it is met, so its entry may
   * precede that of an earlier object still downloading.
   */
  private void enqueueDownload(
      S3Scope scope,
      S3ObjectSummary object,
      String filePath,
      String fileName,
      String marker,
      UUID folderId)
      throws InterruptedException {
    int concurrency = properties.downloadConcurrency();
    if (concurrency <= 1) {
      S3Download download = fetch(scope, object, filePath);
      if (download != null) {
        ingest(scope, object, filePath, fileName, marker, folderId, download);
      }
      return;
    }
    while (pending.size() >= concurrency) {
      drainOne();
    }
    Future<S3Download> future =
        downloadPool()
            .submit(
                () -> {
                  S3Download download =
                      store.getObject(
                          scope.bucket(), object.key(), properties.maxObjectSizeBytes());
                  landed.add(download.file());
                  return download;
                });
    pending.add(new PendingDownload(scope, object, filePath, fileName, marker, folderId, future));
  }

  private ExecutorService downloadPool() {
    if (downloadPool == null) {
      String library = frame.library().getId().toString();
      downloadPool =
          Executors.newFixedThreadPool(
              properties.downloadConcurrency(),
              task -> {
                Thread thread =
                    new Thread(
                        task, "s3-download-" + library + "-" + downloadThreads.incrementAndGet());
                thread.setDaemon(true);
                return thread;
              });
    }
    return downloadPool;
  }

  /**
   * Ingests the oldest download in flight, waiting for it if it is not done yet. A budget spent on
   * the download thread ends the run like one spent on the listing thread.
   */
  private void drainOne() throws InterruptedException {
    PendingDownload item = pending.poll();
    if (item == null) {
      return;
    }
    S3Download download;
    try {
      download = item.download().get();
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RequestBudgetExhaustedException exhausted) {
        throw exhausted;
      }
      if (e.getCause() instanceof S3AccessException failure) {
        handleObjectFailure(item.filePath(), failure);
      } else {
        frame.recordFailure(item.filePath(), e.getCause() == null ? e : e.getCause());
      }
      return;
    }
    ingest(
        item.scope(),
        item.object(),
        item.filePath(),
        item.fileName(),
        item.marker(),
        item.folderId(),
        download);
  }

  private void drainAll() throws InterruptedException {
    while (!pending.isEmpty()) {
      drainOne();
    }
  }

  /**
   * The folder the object's key maps to (ADR-0027, Entscheidung 5), materialised through the run's
   * mirror but not yet pinned against pruning - only a row pins its folder ({@link #mirrorFolder},
   * the post-ingest step), so a folder whose only object never became a document is empty and goes
   * with the run. A segment no folder row can carry leaves the object at the root, a chain deeper
   * than the folder limit is cut, a failing folder layer leaves the object at the root as well -
   * each with a warning, never with a made-up name and never at the document's expense.
   */
  private UUID folderFor(S3Scope scope, String key, String filePath) {
    SourceFolderPath path = S3FolderPath.of(scope, key, scopeRootChain);
    if (path.rejected()) {
      log.warn(
          "Cannot map key segment \"{}\" of {} to a folder name - leaving the document at the"
              + " library root",
          path.rejectedSegment(),
          filePath);
    } else if (path.truncated()) {
      log.warn(
          "Key {} nests deeper than {} folders - placing the document in the deepest allowed one",
          filePath,
          SourceFolderPath.MAX_DEPTH);
    }
    try {
      return folderMirror.folderFor(path.segments());
    } catch (Exception e) {
      log.warn("Failed to mirror the source folder of {} - leaving it at the root", filePath, e);
      return null;
    }
  }

  /**
   * Places an existing row in {@code folderId} and pins the folder; the row's attachments follow
   * only when the row actually moved - in the steady state they already sit where the row does, and
   * a child walk per object would double the run's database round trips.
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

  /** The {@code HeadObject} of an extension-less key: admitted when its content type is. */
  private boolean headAdmits(String bucket, String key, String filePath)
      throws InterruptedException {
    S3ObjectHead head;
    try {
      head = store.headObject(bucket, key);
    } catch (S3AccessException e) {
      return handleObjectFailure(filePath, e);
    }
    if (head.archived()) {
      skip(
          IndexingEventCategory.REJECTED, archivedMessage(filePath, head.storageClass()), filePath);
      return false;
    }
    if (SupportedDocumentFormats.extensionForContentType(head.contentType()) == null) {
      skip(
          IndexingEventCategory.UNSUPPORTED_FORMAT,
          UNSUPPORTED_FORMAT_MESSAGE
              + " (Content-Type "
              + (head.contentType() == null ? "unbekannt" : head.contentType())
              + ")",
          filePath);
      return false;
    }
    return true;
  }

  /** The object's bytes in a temp file, or {@code null} once the failure was handled. */
  private S3Download fetch(S3Scope scope, S3ObjectSummary object, String filePath)
      throws InterruptedException {
    try {
      return store.getObject(scope.bucket(), object.key(), properties.maxObjectSizeBytes());
    } catch (S3AccessException e) {
      handleObjectFailure(filePath, e);
      return null;
    }
  }

  private void ingest(
      S3Scope scope,
      S3ObjectSummary object,
      String filePath,
      String fileName,
      String marker,
      UUID folderId,
      S3Download download)
      throws InterruptedException {
    Path file = download.file();
    try {
      String changeMarker =
          marker != null
              ? marker
              : S3ChangeMarker.of(download.eTag(), download.size(), download.lastModified());
      SourceDocumentContext context =
          new SourceDocumentContext(scope.bucket(), hierarchyPath(scope, object.key()));
      ReconcilingAttachmentAccess attachmentAccess = frame.attachmentAccess(context);
      FileProcessingResult result =
          fileProcessingService.ingest(
              DocumentIngest.builder(frame.library())
                  .file(file, download.size())
                  .filePath(filePath)
                  .fileName(fileName)
                  .sourceType(DocumentSourceType.S3)
                  .context(context)
                  .changeMarker(changeMarker)
                  .folder(folderId)
                  .build(),
              attachmentAccess);
      if (frame.recordOutcome(result, filePath)) {
        frame.markReprocessed(filePath);
        log.info("Indexed S3 object: {}", filePath);
        // the row exists now and pins its folder; the mail attachments a re-parse enumerated are
        // new rows without a folder of their own, so the children are walked unconditionally
        folderMirror.markSeen(folderId);
        documentRepository
            .findByLibraryIdAndFilePath(frame.library().getId(), filePath)
            .ifPresent(document -> applyFolder(document, folderId));
      } else if (result == FileProcessingResult.SKIPPED) {
        // a new feature over the same bytes (multipart re-upload, re-encryption): the row keeps
        // its id and chunks, only the feature was refreshed
        log.info(
            "S3 object {} changed its feature but not its checksum, provenance refreshed",
            filePath);
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
   * What one object's failed head or download means: a missing object is the positive finding a
   * deletion needs (Entscheidung 3) and leaves the reconciliation set, a refused read or an archive
   * state keeps the stored version, a store-wide failure (credentials, clock, TLS, blocked target,
   * wrong region, unreachable) fails the run, and anything else - a throttle that outlasted its
   * retries, an unexpected answer for this one object - counts as failed while the run goes on.
   *
   * @return always {@code false}: the object is not admitted to a download
   */
  private boolean handleObjectFailure(String filePath, S3AccessException e) {
    switch (e) {
      case S3AccessException.ObjectNotFound gone -> {
        frame.markAbsent(filePath);
        skip(IndexingEventCategory.REJECTED, gone.getMessage() + GONE_SUFFIX, filePath);
      }
      case S3AccessException.ReadForbidden forbidden ->
          skip(
              IndexingEventCategory.REJECTED,
              forbidden.getMessage() + UNREADABLE_OBJECT_SUFFIX,
              filePath);
      case S3AccessException.Archived archived ->
          skip(IndexingEventCategory.REJECTED, archived.getMessage(), filePath);
      case S3AccessException.ObjectTooLarge tooLarge ->
          skip(IndexingEventCategory.REJECTED, tooLarge.getMessage(), filePath);
      default -> {
        if (failsTheRun(e)) {
          throw new IndexingRunFailedException(e.getMessage(), e);
        }
        frame.events().record(IndexingEventCategory.UNREACHABLE, e.getMessage(), filePath);
        frame.progress().recordFailed();
      }
    }
    return false;
  }

  /** A failure that no later object of this run will do better with. */
  private static boolean failsTheRun(S3AccessException e) {
    return e instanceof S3AccessException.Authentication
        || e instanceof S3AccessException.ClockSkew
        || e instanceof S3AccessException.Tls
        || e instanceof S3AccessException.TargetBlocked
        || e instanceof S3AccessException.WrongRegionOrStyle
        || e instanceof S3AccessException.Unreachable;
  }

  private void skip(IndexingEventCategory category, String message, String filePath) {
    frame.events().record(category, message, filePath);
    frame.progress().recordSkipped();
  }

  /** One note per run for what was skipped in bulk - the protocol holds 500 entries. */
  private void recordSummaries() {
    if (folderMarkers > 0) {
      frame
          .events()
          .recordRunNote(
              IndexingEventCategory.UNSUPPORTED_FORMAT, folderMarkers + FOLDER_MARKERS_SUFFIX);
    }
    if (excludedKeys > 0) {
      frame
          .events()
          .recordRunNote(IndexingEventCategory.REJECTED, excludedKeys + EXCLUDED_KEYS_SUFFIX);
    }
    frame.events().recordRunNote(IndexingEventCategory.SUMMARY, summaryMessage());
  }

  /** The run's figures in one German sentence - what an operator reads throughput against. */
  private boolean eventRun() {
    return frame.runMode() == IndexingRunMode.EVENT;
  }

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
            .append(eventRun() ? "" : excludedKeys + " durch Muster ausgeschlossen, ")
            .append(frame.progress().skippedCount())
            .append(" übersprungen, ")
            .append(frame.progress().processedCount())
            .append(" neu verarbeitet, ")
            .append(frame.progress().failedCount())
            .append(" fehlgeschlagen");
    if (!scopeDurations.isEmpty()) {
      message.append("; Dauer je Geltungsbereich: ");
      List<String> parts = new ArrayList<>();
      scopeDurations.forEach(
          (key, duration) -> parts.add(key + " " + Math.max(0, duration.toSeconds()) + " s"));
      message.append(String.join(", ", parts));
    }
    return message.toString();
  }

  /** Where the next full sync continues once this one's budget is spent, for the frame's note. */
  private String fullSyncContinuation() {
    return "der Lauf endet unvollständig, der nächste Lauf listet alle Geltungsbereiche erneut und"
        + " lädt nur, was noch fehlt"
        + (unlistableScopeKeys.isEmpty()
            ? ""
            : "; bis dahin nicht auflistbar: " + String.join(", ", unlistableScopeKeys));
  }

  private String tooManyObjectsMessage() {
    return "Die Geltungsbereiche dieser Bibliothek listen mehr als "
        + properties.maxObjectsPerRun()
        + " Objekte; so viele verarbeitet ein Lauf nicht. Bitte die Geltungsbereiche enger"
        + " fassen (Präfixe) oder die Bibliothek aufteilen.";
  }

  private static String archivedMessage(String filePath, String storageClass) {
    return "Das Objekt „"
        + filePath.substring("s3://".length())
        + "“ liegt in der Archivklasse "
        + (storageClass == null ? "(unbekannt)" : storageClass)
        + " und ist ohne Wiederherstellung nicht lesbar.";
  }

  /** {@code s3://<bucket>/<key>}, the key as it is - the identity per library (Entscheidung 5). */
  static String filePath(String bucket, String key) {
    return "s3://" + bucket + "/" + key;
  }

  private static boolean hasExtension(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot > 0 && dot < fileName.length() - 1;
  }

  /**
   * The folder chain of {@code key} below the scope's prefix, joined with {@link
   * SourceDocumentContext#HIERARCHY_SEPARATOR}; {@code null} for a key directly under the prefix.
   * Deliberately not the mirrored folder chain ({@link S3FolderPath}): the hierarchy path is
   * prefix-relative and complete, whatever the number of scopes, the folder limit or a segment no
   * folder row can carry.
   */
  static String hierarchyPath(S3Scope scope, String key) {
    String relative = key.startsWith(scope.prefix()) ? key.substring(scope.prefix().length()) : key;
    int lastSlash = relative.lastIndexOf('/');
    if (lastSlash <= 0) {
      return null;
    }
    List<String> segments = new ArrayList<>();
    for (String segment : relative.substring(0, lastSlash).split("/")) {
      if (!segment.isEmpty()) {
        segments.add(segment);
      }
    }
    return segments.isEmpty()
        ? null
        : String.join(SourceDocumentContext.HIERARCHY_SEPARATOR, segments);
  }
}
