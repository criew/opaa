package io.opaa.indexing.filesync;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.common.ByteSizes;
import io.opaa.format.SupportedDocumentFormats;
import io.opaa.indexing.document.DocumentIngest;
import io.opaa.indexing.document.DocumentIngestResult;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.RequestBudgetExhaustedException;
import io.opaa.indexing.job.RunEndingFailures;
import io.opaa.indexing.maintenance.StaleDocumentCleanupService;
import io.opaa.indexing.source.IndexingRun;
import io.opaa.indexing.source.IndexingRunFailedException;
import io.opaa.indexing.source.ListingOutcome;
import io.opaa.indexing.source.ReconcilingAttachmentAccess;
import io.opaa.indexing.source.ScanJournal;
import io.opaa.indexing.source.SourceCredentialsRejectedException;
import io.opaa.indexing.source.SourceFolderMirror;
import io.opaa.indexing.source.SourceFolderPath;
import io.opaa.indexing.source.SourceSyncState;
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
import java.util.HashMap;
import java.util.HashSet;
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
 * The file sync of one library over one open {@link FileStore} (ADR-0040, Entscheidung 1): every
 * listed entry is present whatever its outcome, and the change feature decides before any download.
 * A full sync is a round that may span several runs ({@link ScanRound}); it is {@link
 * ListingOutcome.Complete} when this run listed every container whole or the round's {@link
 * AbsenceProof} covers the runs before. An unlistable container keeps its bestand; a {@link
 * FileAccessException.RunEnding} fails the run. Downloads run concurrently but are ingested in
 * listing order; {@link #close()} ends every download thread and deletes every temp file. Every
 * access to the store first asks the run's credentials, so a refused source ends the run there.
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
  public static final String UNPROVEN_ROUND_MESSAGE =
      "Vollabgleich über mehrere Läufe abgeschlossen; in der Quelle gelöschte Dateien entfernt erst"
          + " ein Lauf, der alle Geltungsbereiche allein auflistet";
  public static final String RESTART_SUFFIX =
      " Die Auflistung dieses Geltungsbereichs beginnt neu.";
  public static final String OVERSIZED_CHECKPOINT_MESSAGE =
      "Der Fortsetzungspunkt ist mit %d Zeichen zu groß zum Speichern; endet ein Lauf am"
          + " Anfragebudget, setzt der nächste diesen Bereich nicht fort. Den Bereich in mehrere"
          + " Ordner aufteilen.";

  private final IndexingRun frame;
  private final FileStore store;
  private final FileSyncSettings settings;
  private final FileSyncWording wording;
  private final DocumentIngestService documentIngestService;
  private final DocumentRepository documentRepository;
  private final StaleDocumentCleanupService cleanupService;
  private final SourceFolderMirror folderMirror;
  private SourceSyncState state;
  private final ScanJournal journal;
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

  /** The round of a full sync; {@code null} in an event or change run. */
  private ScanRound round;

  /** The first page of the current container not yet visited completely. */
  private int visitingFrom = 1;

  /** Per path a listing met, the page it was first met on. */
  private final Map<String, Integer> firstMetOn = new HashMap<>();

  /** The containers whose store reports folders: their entries are checked against their place. */
  private final Set<String> reportingFolders = new HashSet<>();

  private final Map<String, Duration> containerDurations = new LinkedHashMap<>();
  private final Map<String, Long> notADocumentNotes = new LinkedHashMap<>();
  private final Map<String, Long> deselectedNotes = new LinkedHashMap<>();
  private int total;

  /** Failures that may pass by themselves - they hold a change stream's cursor. */
  private int transientFailures;

  /** Containers a change run added to or removed from. */
  private final Set<String> changedContainers = new LinkedHashSet<>();

  private final List<PendingRemoval> pendingRemovals = new ArrayList<>();

  /** The new cursor of every stream read cleanly, written once the run's removals are done. */
  private final Map<String, String> pendingCursors = new LinkedHashMap<>();

  private long listed;
  private long deselected;

  /** One entry fetched off the listing thread, with its folder and the page that listed it. */
  private record PendingDownload(
      FileEntry entry, UUID folderId, int page, Future<FetchedFile> download) {}

  /** How the listing of one container ended in this run. */
  private enum Listing {
    COMPLETE,
    UNLISTED,
    RESTARTED
  }

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
      ScanJournal journal,
      Clock clock,
      SupportedDocumentFormats supportedFormats) {
    this.frame = frame;
    this.store = new SecretCheckedStore(store, frame.credentials());
    this.settings = settings;
    this.wording = wording;
    this.documentIngestService = documentIngestService;
    this.documentRepository = documentRepository;
    this.cleanupService = cleanupService;
    this.folderMirror = new SourceFolderMirror(folderService, frame.library());
    this.state = state;
    this.journal = journal;
    this.clock = clock;
    this.supportedFormats = supportedFormats;
  }

  /** The full sync over every container of the store, as far as this run's budget goes. */
  public ListingOutcome run() throws InterruptedException {
    List<FileContainer> containers = store.containers();
    boolean resumed = state.isFullSyncInterrupted();
    round =
        ScanRound.begin(
            state,
            containers,
            journal,
            frame,
            documentRepository,
            store.absenceProof(),
            clock,
            settings,
            supportedFormats);
    state = round.state();
    holdStartCursors(containers, resumed);
    round.save();
    frame.budgetContinuation(this::fullSyncContinuation);
    frame.budgetStallAdvice(wording.budgetStallAdvice());
    try {
      for (FileContainer container = round.next(); container != null; container = round.next()) {
        Instant start = clock.instant();
        store.recall(container, round.memory().recallFor(container));
        Listing listing = listContainer(container);
        drainAll();
        containerDurations.merge(
            container.key(), Duration.between(start, clock.instant()), Duration::plus);
        if (listing == Listing.COMPLETE) {
          round.completed();
        } else if (listing == Listing.UNLISTED) {
          round.unlisted();
        }
      }
    } catch (RequestBudgetExhaustedException e) {
      drainUntilRefused();
      if (round.budgetSpent(unsettledFrom(), firstMetOn)) {
        // a later checkpoint or a completed container: the chain of runs moves on
        frame.budgetStallAdvice(null);
      }
      throw e;
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
      if (round.resumes()) {
        // every other container is done: a round kept open would never list them again
        round.abandon();
      } else {
        round.continuesLater();
      }
      return ListingOutcome.incomplete(List.copyOf(unlistedContainerKeys));
    }
    if (!round.provenByThisRun()) {
      if (round.proof() == AbsenceProof.SINGLE_RUN) {
        // the runs before may have missed a file that moved into a part already listed
        round.finish(false);
        frame.events().recordRunNote(IndexingEventCategory.SUMMARY, UNPROVEN_ROUND_MESSAGE);
        return ListingOutcome.truncated();
      }
      round.presenceToFrame();
    }
    // Folders are pruned after the document cleanup of a complete listing, so a folder emptied by
    // it goes in the same run (ADR-0020); without the reconciliation the round stays open.
    frame.afterReconciliation(
        reconciled -> {
          folderMirror.prune();
          if (reconciled) {
            round.finish(true);
          } else {
            frame
                .events()
                .recordRunNote(IndexingEventCategory.ERROR, RECONCILIATION_FAILED_MESSAGE);
          }
        });
    return ListingOutcome.complete();
  }

  /** The first page not yet ingested completely: one still being visited or downloaded. */
  private int unsettledFrom() {
    PendingDownload oldest = pending.peek();
    return oldest == null ? visitingFrom : Math.min(visitingFrom, oldest.page());
  }

  /**
   * The event run: one {@link FileStore#head} per reported file, then the full sync's own visit for
   * a present file and a removal with attachments for a {@link FileAccessException.Gone}. No
   * listing and {@link ListingOutcome#partial()}, so the frame reconciles nothing; an open round
   * learns what it confirmed.
   *
   * @param outside reported references outside every container, counted as skipped
   * @param dropped references the connector dropped before, noted once
   */
  public ListingOutcome refresh(List<FileReference> references, int outside, int dropped)
      throws InterruptedException {
    frame.progress().setTotal(references.size() + outside);
    frame.progress().report();
    // no next event run continues this batch: the scheduled run covers the rest
    frame.budgetContinuation(wording::eventRunContinuation);
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
      ScanRound.confirm(journal, state, frame, store.absenceProof());
      recordSummaries();
    }
    return ListingOutcome.partial();
  }

  /**
   * The change run (ADR-0040, Entscheidung 6): every stream is read from its stored cursor. A
   * reported file goes the full sync's way; a deselected one and a reported removal take the
   * document with its attachments - a removal only for a document of the stream's own containers,
   * and only while all of them are reachable. A stream's new cursor is kept unless a file failed
   * transiently; a durable failure (an unreadable format) does not hold it. The cursors move only
   * once every stream is read and the removals are applied, so a run that ends early loses none. A
   * change of structure, an expired cursor or a stream without a cursor make the next run a full
   * sync. The folder memory of every container the run changed is dropped. A new container on an
   * existing stream has no full listing behind it: the connector discards the run state when its
   * containers change. No listing, no reconciliation: {@link ListingOutcome#partial()}.
   */
  public ListingOutcome runChanges() throws InterruptedException {
    ChangeFeed feed =
        store.changes().orElseThrow(() -> new IllegalStateException("the store has no change log"));
    Map<String, List<FileContainer>> streams = new LinkedHashMap<>();
    for (FileContainer container : store.containers()) {
      streams.computeIfAbsent(feed.feedKey(container), key -> new ArrayList<>()).add(container);
    }
    Map<String, String> cursors = state.changeCursors();
    frame.budgetContinuation(wording::eventRunContinuation);
    try {
      for (Map.Entry<String, List<FileContainer>> stream : streams.entrySet()) {
        String cursor = cursors.get(stream.getKey());
        if (cursor == null) {
          state.requireFullSync();
          frame.events().recordRunNote(IndexingEventCategory.SUMMARY, wording.fullSyncFollows());
          continue;
        }
        readStream(feed, stream.getKey(), stream.getValue(), cursor);
      }
      applyRemovals();
      // all at once, after the removals: a run that ends early moves no cursor past a removal
      pendingCursors.forEach(state::advanceChangeCursor);
    } finally {
      forgetChangedContainers();
      state = journal.save(state);
      ScanRound.confirm(journal, state, frame, store.absenceProof());
      recordSummaries();
    }
    return ListingOutcome.partial();
  }

  private void readStream(
      ChangeFeed feed, String feedKey, List<FileContainer> containers, String cursor)
      throws InterruptedException {
    boolean reachable = true;
    for (FileContainer container : containers) {
      try {
        feed.requireReachable(container);
      } catch (FileAccessException.ContainerUnlistable e) {
        reachable = false;
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
      } catch (FileAccessException e) {
        throw runFailure(e);
      }
    }
    Set<String> own = new LinkedHashSet<>();
    containers.forEach(container -> own.add(container.key()));
    int transientBefore = transientFailures;
    String newStart = null;
    String next = cursor;
    try {
      while (next != null) {
        ChangePage page = feed.read(feedKey, next);
        total += page.changes().size();
        frame.progress().setTotal(total);
        frame.progress().report();
        for (Change change : page.changes()) {
          apply(change, reachable, own);
          frame.progress().report();
        }
        drainAll();
        if (page.fullSyncNeeded()) {
          state.requireFullSync();
        }
        next = page.next();
        newStart = page.newStart();
      }
    } catch (FileAccessException.CursorExpired e) {
      log.info("Change cursor of stream {} expired: {}", feedKey, e.getMessage());
      state.discardChangeCursor(feedKey);
      state.requireFullSync();
      frame
          .events()
          .recordRunNote(
              IndexingEventCategory.REJECTED, e.getMessage() + " " + wording.fullSyncFollows());
      return;
    } catch (FileAccessException.RunEnding e) {
      throw runFailure(e);
    } catch (FileAccessException e) {
      // the stream stays where it was; the next run reads it again
      frame.events().record(IndexingEventCategory.UNREACHABLE, e.getMessage(), feedKey);
      frame.progress().recordFailed();
      return;
    }
    if (reachable && transientFailures == transientBefore) {
      pendingCursors.put(feedKey, newStart);
    }
  }

  /**
   * One reported change. A removal counts only for a document of {@code own} - another stream
   * reports the files of its containers - and only while those are reachable.
   */
  private void apply(Change change, boolean reachable, Set<String> own)
      throws InterruptedException {
    switch (change) {
      case Change.Removed removed -> {
        if (reachable) {
          // judged once every stream is read: another stream may report the file moved to its area
          pendingRemovals.add(new PendingRemoval(removed.filePath(), own));
        } else {
          frame.progress().recordSkipped();
        }
      }
      case Change.Updated updated -> {
        FileEntry entry = updated.entry();
        changedContainers.add(entry.container().key());
        if (entry.exclusion() instanceof Exclusion.Deselected) {
          // outside the patterns now: not part of the bestand, as in a full sync
          removeGone(entry.filePath());
        } else {
          listed++;
          visit(entry, 0);
        }
      }
    }
  }

  /**
   * The removals the streams reported, each for a document that still belongs to one of its
   * stream's containers - a document another stream placed elsewhere this run stays.
   */
  private void applyRemovals() {
    for (PendingRemoval removal : pendingRemovals) {
      String container =
          documentRepository
              .findByLibraryIdAndFilePath(frame.library().getId(), removal.filePath())
              .map(Document::getSourceContainerKey)
              .orElse(null);
      if (container == null || removal.own().contains(container)) {
        if (container != null) {
          changedContainers.add(container);
        }
        removeGone(removal.filePath());
      } else {
        frame.progress().recordSkipped();
      }
    }
    pendingRemovals.clear();
  }

  /** A removal a stream reported, with the containers that stream serves. */
  private record PendingRemoval(String filePath, Set<String> own) {}

  /** Drops the folder memory of every container a change run touched. */
  private void forgetChangedContainers() {
    SourceSyncState.SubtreeMemory memory = state.subtreeMemory();
    if (changedContainers.isEmpty() || memory.containers().isEmpty()) {
      return;
    }
    Map<String, Map<String, String>> kept = new LinkedHashMap<>(memory.containers());
    kept.keySet().removeAll(changedContainers);
    state.rememberSubtrees(
        new SourceSyncState.SubtreeMemory(memory.basis(), memory.establishedAt(), kept));
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
    if (entry.exclusion() instanceof Exclusion.Deselected) {
      // outside the patterns: not part of the bestand, neither present nor fetched
      frame.progress().recordSkipped();
      return;
    }
    if (entry.exclusion() instanceof Exclusion.Unavailable unavailable) {
      frame.markPresent(entry.filePath());
      skip(IndexingEventCategory.REJECTED, unavailable.message(), entry.filePath());
      return;
    }
    listed++;
    visit(entry, 0);
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

  /** Whether {@code path} is the folder {@code folder} or lies below it. */
  static boolean covers(String folder, String path) {
    return FolderMemory.covers(folder, path);
  }

  /** The entry's stored state does not reflect this run: its folders are listed again next time. */
  private void unsettle(FileEntry entry) {
    if (round != null) {
      round.memory().unsettle(entry);
    }
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
      throw runFailure(e);
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
   * Lists {@code container} from its checkpoint, or from its first page, to its last page. A
   * container the store cannot list stays out of the round's completed ones; one whose checkpoint
   * expired starts over.
   */
  private Listing listContainer(FileContainer container) throws InterruptedException {
    String checkpoint = round.start(container);
    String continuation = null;
    boolean first = true;
    do {
      int pageNumber = round.nextPage();
      visitingFrom = pageNumber;
      FilePage page;
      try {
        page =
            first && checkpoint != null
                ? store.resume(container, checkpoint)
                : store.list(container, continuation);
      } catch (FileAccessException.CheckpointExpired e) {
        return restart(container, e);
      } catch (FileAccessException.ContainerUnlistable e) {
        // no deletion finding: the run says so, the rest is still processed, nothing reconciled
        log.warn(
            "Container {} not listable for library {}: {}",
            container.key(),
            frame.library().getId(),
            e.getMessage());
        notListed(container, e.getMessage() + UNLISTABLE_CONTAINER_SUFFIX);
        return Listing.UNLISTED;
      } catch (FileAccessException e) {
        throw runFailure(e);
      }
      if (first && checkpoint != null) {
        round.resumed();
      }
      first = false;
      if (round.listed(pageNumber, page.checkpoint(), page.entries().size(), unsettledFrom())) {
        frame
            .events()
            .record(
                IndexingEventCategory.REJECTED,
                "Geltungsbereich „"
                    + container.key()
                    + "“: "
                    + String.format(OVERSIZED_CHECKPOINT_MESSAGE, page.checkpoint().length()),
                container.key());
      }
      listed += page.entries().size();
      if (round.entriesListed() > settings.maxEntriesPerRun()) {
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
      if (!page.unchangedSubtrees().isEmpty() || !page.listedSubtrees().isEmpty()) {
        requireContainerContext(container, page);
        reportingFolders.add(container.key());
        Map<String, String> handedOver = round.memory().recalled(container.key());
        String unknown =
            page.unchangedSubtrees().stream()
                .filter(folder -> !handedOver.containsKey(folder))
                .findFirst()
                .orElse(null);
        if (unknown != null) {
          // a folder whose marker was never handed over cannot be known unchanged: keep the bestand
          log.warn(
              "Store reported folder \"{}\" of {} unchanged without a recalled marker",
              unknown,
              container.key());
          notListed(
              container,
              "Der Ordner „"
                  + unknown
                  + "“ wurde ohne Prüfung als unverändert gemeldet."
                  + UNLISTABLE_CONTAINER_SUFFIX);
          return Listing.UNLISTED;
        }
        round.memory().listed(container.key(), page.listedSubtrees());
      }
      for (String subtree : page.unchangedSubtrees()) {
        keepUnchangedSubtree(container, subtree, pageNumber);
      }
      for (FileEntry entry : admitted) {
        visit(entry, pageNumber);
      }
      visitingFrom = pageNumber + 1;
      drainFinished();
      continuation = page.next();
    } while (continuation != null);
    return Listing.COMPLETE;
  }

  /**
   * The container's checkpoint expired: it starts over behind the other containers, or - expired
   * again - is not listed in this run.
   */
  private Listing restart(FileContainer container, FileAccessException.CheckpointExpired e) {
    if (round.expired(container)) {
      frame
          .events()
          .record(
              IndexingEventCategory.REJECTED,
              "Geltungsbereich „" + container.key() + "“: " + e.getMessage() + RESTART_SUFFIX,
              container.key());
    } else {
      notListed(container, e.getMessage() + UNLISTABLE_CONTAINER_SUFFIX);
    }
    return Listing.RESTARTED;
  }

  /** Names a container this run did not list completely; nothing is reconciled. */
  private void notListed(FileContainer container, String reason) {
    frame
        .events()
        .record(
            IndexingEventCategory.REJECTED,
            "Geltungsbereich „" + container.key() + "“: " + reason,
            container.key());
    unlistedContainerKeys.add(container.key());
  }

  /**
   * Every stored row of the container in or below {@code folder} stays present and keeps its
   * folder; the recalled markers there carry over to the next run.
   */
  private void keepUnchangedSubtree(FileContainer container, String folder, int page) {
    UUID libraryId = frame.library().getId();
    List<Document> documents =
        folder.isEmpty()
            ? documentRepository.findByLibraryIdAndSourceContainerKey(libraryId, container.key())
            : documentRepository.findInHierarchy(libraryId, container.key(), folder);
    for (Document document : documents) {
      frame.markPresent(document.getFilePath());
      firstMetOn.putIfAbsent(document.getFilePath(), page);
      folderMirror.markSeen(document.getFolderId());
    }
    round.memory().carry(container.key(), folder);
  }

  /** A store that reports folders names the container in every entry's context (FileStore#list). */
  private static void requireContainerContext(FileContainer container, FilePage page) {
    for (FileEntry entry : page.entries()) {
      if (!container.key().equals(entry.context().containerKey())) {
        throw new IllegalStateException(
            "entry "
                + entry.filePath()
                + " of a store reporting folders carries another container key than "
                + container.key());
      }
    }
  }

  /**
   * One listed entry: present from the first look, then skipped for what the listing already shows
   * (no document, an unsupported extension, unavailable, oversize), skipped without a download when
   * the change feature is already stored, otherwise fetched and handed to the document path. A name
   * without an extension costs one {@link FileStore#head} whose media type decides.
   */
  private void visit(FileEntry entry, int page) throws InterruptedException {
    String filePath = entry.filePath();
    frame.markPresent(filePath);
    if (page > 0) {
      firstMetOn.putIfAbsent(filePath, page);
    }
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
    existing.ifPresent(document -> unsettleOldPlace(document, entry));
    existing.ifPresent(document -> placeSeen(document, entry, folderId));
    if (entry.exclusion() instanceof Exclusion.Unavailable unavailable) {
      // it may become readable without its folder changing: not settled
      unsettle(entry);
      skip(IndexingEventCategory.REJECTED, unavailable.message(), filePath);
      return;
    }
    if (entry.size() > settings.maxFileSizeBytes()) {
      existing.ifPresent(document -> unsettle(entry));
      skip(
          IndexingEventCategory.REJECTED,
          wording.tooLarge(entry, settings.maxFileSizeBytes()),
          filePath);
      return;
    }
    String marker = entry.changeMarker();
    // For a store that reports folders the row must also stand at the listed place - a folder is
    // kept by the hierarchy path of its rows; a renamed or moved file is fetched once and moves.
    boolean reportsFolders =
        reportingFolders.contains(entry.container().key())
            || (round != null && round.memory().reportsFolders(entry.container().key()));
    if (marker != null
        && existing
            .filter(document -> document.isUnchangedAt(marker))
            .filter(
                document ->
                    !reportsFolders
                        || (document.holdsSourceContext(entry.context())
                            && entry.fileName().equals(document.getFileName())))
            .isPresent()) {
      log.debug("Skipping unchanged file: {}", filePath);
      frame.progress().recordSkipped();
      return;
    }
    if (!supportedByName && !headAdmits(entry)) {
      existing.ifPresent(document -> unsettle(entry));
      return;
    }
    enqueueDownload(entry, existing.isPresent() ? folderId : folderFor(entry), page);
  }

  /**
   * Fetches the entry off the listing thread when downloads may run concurrently, serially
   * otherwise; with {@code downloadConcurrency} downloads in flight the oldest is ingested first.
   * An entry skipped without a download is noted the moment it is met, so its protocol entry may
   * precede that of an earlier one still downloading.
   */
  private void enqueueDownload(FileEntry entry, UUID folderId, int page)
      throws InterruptedException {
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
    pending.add(new PendingDownload(entry, folderId, page, future));
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
   * Ingests the oldest download in flight, waiting for it if needed. A budget spent or a source
   * refused on the download thread ends the run like on the listing thread; a download the budget
   * refused stays pending, so its page counts as not ingested.
   */
  private void drainOne() throws InterruptedException {
    PendingDownload item = pending.peek();
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
      pending.poll();
      RuntimeException ending = RunEndingFailures.endingCause(e.getCause());
      if (ending != null) {
        throw ending;
      }
      unsettle(item.entry());
      if (e.getCause() instanceof FileAccessException failure) {
        handleFailure(item.entry().filePath(), failure);
      } else {
        transientFailures++;
        frame.recordFailure(item.entry().filePath(), e.getCause() == null ? e : e.getCause());
      }
      return;
    }
    pending.poll();
    ingest(item.entry(), item.folderId(), fetched);
  }

  /** Ingests the downloads already finished, oldest first, so their pages settle early. */
  private void drainFinished() throws InterruptedException {
    while (!pending.isEmpty() && pending.peek().download().isDone()) {
      drainOne();
    }
  }

  /**
   * At the budget's end: ingests the downloads in flight up to the first one the budget refused, so
   * what was already fetched counts for the checkpoint.
   */
  private void drainUntilRefused() throws InterruptedException {
    try {
      drainAll();
    } catch (RequestBudgetExhaustedException refused) {
      // the refused download stays pending: its page is not settled
    }
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

  /** A row met at another place keeps its old folder out of the round's memory. */
  private void unsettleOldPlace(Document document, FileEntry entry) {
    if (round != null
        && document.getSourceContainerKey() != null
        && !document.holdsSourceContext(entry.context())) {
      round.memory().unsettle(document.getSourceContainerKey(), document.getSourceHierarchyPath());
    }
  }

  /**
   * Places an existing row in {@code folderId} and pins the folder; its attachments follow only
   * when the row actually moved.
   */
  /**
   * Mirrors the folder of a stored row and moves it to the container it was seen in: that container
   * decides which change stream may report it removed. One save covers both.
   */
  private void placeSeen(Document document, FileEntry entry, UUID folderId) {
    boolean otherContainer =
        !Objects.equals(document.getSourceContainerKey(), entry.context().containerKey());
    if (otherContainer) {
      document.applySourceContext(entry.context());
    }
    boolean folderMoves = !Objects.equals(document.getFolderId(), folderId);
    mirrorFolder(document, folderId);
    if (otherContainer && !folderMoves) {
      documentRepository.save(document);
    }
  }

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
      unsettle(entry);
      return handleFailure(entry.filePath(), e);
    }
    if (head.exclusion() instanceof Exclusion.Deselected) {
      frame.progress().recordSkipped();
      return false;
    }
    if (head.exclusion() instanceof Exclusion.Unavailable unavailable) {
      unsettle(entry);
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
      unsettle(entry);
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
      int rejectedBefore = frame.progress().personalQuotaRejections();
      DocumentIngestResult result =
          documentIngestService.ingest(
              DocumentIngest.builder(frame.library())
                  .file(file, fetched.size())
                  .filePath(filePath)
                  .fileName(fetched.fileName() != null ? fetched.fileName() : entry.fileName())
                  .sourceType(frame.sourceType())
                  .context(entry.context())
                  .changeMarker(changeMarker)
                  .folder(folderId)
                  .build(),
              attachmentAccess);
      if (result != DocumentIngestResult.PROCESSED
          && result != DocumentIngestResult.SKIPPED
          && result != DocumentIngestResult.NO_EXTRACTABLE_TEXT) {
        unsettle(entry);
      }
      boolean processed = frame.recordOutcome(result, filePath);
      if (frame.progress().personalQuotaRejections() > rejectedBefore) {
        // like a transient failure: the cursor and the folder memory stay for the next run
        unsettle(entry);
        transientFailures++;
      }
      if (processed) {
        frame.markReprocessed(filePath);
        if (fetched.note() != null) {
          frame.events().record(IndexingEventCategory.FORMAT_MISMATCH, fetched.note(), filePath);
        }
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
      unsettle(entry);
      IndexingRun.rethrowRunEnding(e);
      transientFailures++;
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
      case FileAccessException.RunEnding runEnding -> throw runFailure(runEnding);
      default -> {
        transientFailures++;
        frame.events().record(IndexingEventCategory.UNREACHABLE, e.getMessage(), filePath);
        frame.progress().recordFailed();
      }
    }
    return false;
  }

  /** The run's end for a store failure; a rejected secret ends it under its own category. */
  private static IndexingRunFailedException runFailure(FileAccessException e) {
    return e instanceof FileAccessException.CredentialsRejected
        ? new SourceCredentialsRejectedException(e.getMessage(), e)
        : new IndexingRunFailedException(e.getMessage(), e);
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

  /** A run over reported files rather than a listing - an event or a change run. */
  private boolean eventRun() {
    return frame.runMode() != IndexingRunMode.FULL;
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
            .append(
                eventRun()
                    ? wording.checkedSummary(checked)
                    : wording.listedSummary(listed, deselected))
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
    return (round != null && round.resumes()
            ? "der Lauf endet unvollständig, der nächste Lauf setzt die Auflistung hier fort und"
                + " lädt nur, was noch fehlt"
            : "der Lauf endet unvollständig, der nächste Lauf listet alle Geltungsbereiche erneut"
                + " und lädt nur, was noch fehlt")
        + (unlistedContainerKeys.isEmpty()
            ? ""
            : "; bis dahin nicht auflistbar: " + String.join(", ", unlistedContainerKeys));
  }

  private static boolean hasExtension(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot > 0 && dot < fileName.length() - 1;
  }
}
