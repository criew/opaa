package io.opaa.indexing.filesync;

import io.opaa.indexing.source.IndexingRun;
import io.opaa.indexing.source.ScanJournal;
import io.opaa.indexing.source.SourceSyncState;
import io.opaa.indexing.source.SourceSyncState.ContainerProgress;
import io.opaa.indexing.source.SourceSyncState.ScanProgress;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * One full sync round as one run sees it (ADR-0040): which containers this run lists in which
 * order, where each resumes, and what is kept when. A checkpoint is kept only once every download
 * of the pages up to it is ingested or counted as failed, at a container's end or at the orderly
 * end of the budget; any other end keeps what was kept before.
 */
final class ScanRound {

  /** A container whose checkpoint expired this often in a row is not listed in this run. */
  static final int MAX_RESTARTS = 2;

  private final ScanJournal journal;
  private final IndexingRun frame;
  private final AbsenceProof proof;
  private final Clock clock;
  private final FolderMemory memory;
  private final FolderRevisits revisits;
  private final UUID scanId;
  private final Instant startedAt;
  private final Map<String, ContainerProgress> containers = new LinkedHashMap<>();
  private final Deque<FileContainer> queue = new ArrayDeque<>();
  private final Set<String> fromScratch = new HashSet<>();
  private final Set<String> wholeThisRun = new HashSet<>();
  private final Set<String> present = new HashSet<>();
  private final List<String> keys = new ArrayList<>();
  private SourceSyncState state;
  private boolean progressed;

  /** Whether the round started in an earlier run or goes on in a later one. */
  private boolean spansRuns;

  /** Whether the round started in an earlier run. */
  private final boolean continued;

  private String current;
  private long entriesBase;
  private int pageCounter;

  /** The first page of the current container in this run. */
  private int firstPage;

  /** The current container's entries listed in this run. */
  private long listedEntries;

  /** Whether the current container gave a checkpoint in this run. */
  private boolean checkpointed;

  /** Whether the current container gave a checkpoint too long to keep in this run. */
  private boolean oversized;

  /** The last fully ingested page of the current container that gave a checkpoint. */
  private PageMark settled;

  /** The pages of the current container not yet fully ingested, by number. */
  private final TreeMap<Integer, PageMark> unsettled = new TreeMap<>();

  /**
   * One listed page: its number, its checkpoint, if any, and the container's entries through it.
   */
  private record PageMark(int page, String checkpoint, long entries) {}

  private ScanRound(
      ScanJournal journal,
      IndexingRun frame,
      AbsenceProof proof,
      Clock clock,
      FolderMemory memory,
      FolderRevisits revisits,
      SourceSyncState state,
      ScanProgress progress) {
    this.journal = journal;
    this.frame = frame;
    this.proof = proof;
    this.clock = clock;
    this.memory = memory;
    this.revisits = revisits;
    this.state = state;
    this.scanId = progress == null ? UUID.randomUUID() : progress.scanId();
    this.startedAt = progress == null ? clock.instant() : progress.startedAt();
    if (progress != null) {
      containers.putAll(progress.containers());
      spansRuns = true;
    }
    this.continued = progress != null;
  }

  /**
   * Resumes the round {@code state} holds when it still fits {@code stores}'s containers, else
   * starts a new one; begins the full sync under the run's job. Nothing is saved yet.
   *
   * @param cursorsHeld whether the state holds a start cursor for every change stream
   */
  static ScanRound begin(
      SourceSyncState state,
      List<FileContainer> stores,
      ScanJournal journal,
      IndexingRun frame,
      io.opaa.knowledge.DocumentRepository documentRepository,
      AbsenceProof proof,
      Clock clock,
      FileSyncSettings settings,
      io.opaa.format.SupportedDocumentFormats supportedFormats,
      boolean cursorsHeld) {
    ScanProgress progress = resumable(state, stores, proof, cursorsHeld);
    FolderRevisits revisits = FolderRevisits.load(journal, state.getId());
    FolderMemory memory =
        new FolderMemory(
            settings, supportedFormats, documentRepository, frame.library().getId(), revisits);
    memory.recall(state.subtreeMemory(), progress, clock.instant());
    state.beginFullSync(frame.jobId());
    ScanRound round =
        new ScanRound(journal, frame, proof, clock, memory, revisits, state, progress);
    round.order(stores);
    return round;
  }

  /**
   * The round {@code state} holds, or {@code null} for a new one. A round that no longer fits the
   * containers is dropped; so is one begun under another absence proof, whose presence that proof
   * did not write; one whose every container is complete under {@link AbsenceProof#SINGLE_RUN},
   * which can only follow a failed reconciliation and proves nothing; and one under {@link
   * AbsenceProof#CHANGE_FEED} without a start cursor for every stream, whose log would not reach
   * back to the round's beginning.
   */
  private static ScanProgress resumable(
      SourceSyncState state, List<FileContainer> stores, AbsenceProof proof, boolean cursorsHeld) {
    ScanProgress progress = state.scanProgress();
    if (progress == null) {
      return null;
    }
    Set<String> keys = new HashSet<>();
    stores.forEach(container -> keys.add(container.key()));
    Set<String> completed = state.completedScopeKeys();
    boolean fits =
        state.isFullSyncInterrupted()
            && proof.name().equals(progress.proof())
            && keys.containsAll(progress.containers().keySet())
            && keys.containsAll(completed);
    boolean exhausted = proof == AbsenceProof.SINGLE_RUN && completed.containsAll(keys);
    boolean unprovable = proof == AbsenceProof.CHANGE_FEED && !cursorsHeld;
    if (fits && !exhausted && !unprovable) {
      return progress;
    }
    state.discardScan();
    return null;
  }

  /** Open with a checkpoint, open without, restarted, then completed ones without checkpoints. */
  private void order(List<FileContainer> stores) {
    Set<String> completed = state.completedScopeKeys();
    List<FileContainer> withCheckpoint = new ArrayList<>();
    List<FileContainer> open = new ArrayList<>();
    List<FileContainer> restarted = new ArrayList<>();
    List<FileContainer> again = new ArrayList<>();
    for (FileContainer container : stores) {
      keys.add(container.key());
      ContainerProgress progress = containers.get(container.key());
      if (completed.contains(container.key())) {
        if (progress == null || !progress.resumable()) {
          again.add(container);
        }
      } else if (progress != null && progress.checkpoint() != null) {
        withCheckpoint.add(container);
      } else if (progress != null && progress.restarts() > 0) {
        restarted.add(container);
      } else {
        open.add(container);
      }
    }
    queue.addAll(withCheckpoint);
    queue.addAll(open);
    queue.addAll(restarted);
    queue.addAll(again);
  }

  SourceSyncState state() {
    return state;
  }

  FolderMemory memory() {
    return memory;
  }

  /** The next container of this run, {@code null} once every one had its turn. */
  FileContainer next() {
    return queue.poll();
  }

  /**
   * Starts listing {@code container}: its checkpoint when it has one, else {@code null} and the
   * container counts anew from its first page, with the revisits visible now.
   */
  String start(FileContainer container) {
    current = container.key();
    clearPages();
    firstPage = pageCounter + 1;
    ContainerProgress progress = containers.get(current);
    if (progress != null && progress.checkpoint() != null) {
      entriesBase = progress.entries();
      return progress.checkpoint();
    }
    entriesBase = 0;
    fromScratch.add(current);
    containers.put(
        current,
        new ContainerProgress(
            null,
            progress == null ? 0 : progress.restarts(),
            0,
            progress != null && progress.resumable(),
            revisits.idsAtStart(current)));
    return null;
  }

  /** The store accepted the container's checkpoint. */
  void resumed() {
    ContainerProgress progress = containers.get(current);
    containers.put(
        current,
        new ContainerProgress(
            progress.checkpoint(), 0, progress.entries(), true, progress.revisitsSeen()));
  }

  /**
   * The store no longer accepts the checkpoint of {@code container}: it starts over behind the
   * other containers of this run, or - after {@link #MAX_RESTARTS} expiries in a row - not in this
   * run.
   *
   * @return whether it is listed again in this run
   */
  boolean expired(FileContainer container) {
    ContainerProgress progress = containers.get(container.key());
    int restarts = progress.restarts() + 1;
    containers.put(
        container.key(), new ContainerProgress(null, restarts, 0, true, progress.revisitsSeen()));
    clearPages();
    current = null;
    save();
    if (restarts >= MAX_RESTARTS) {
      return false;
    }
    queue.addLast(container);
    return true;
  }

  /** The number of the next page, counted over the whole run. */
  int nextPage() {
    return ++pageCounter;
  }

  /**
   * A page of the current container was listed with {@code entries} entries; pages below {@code
   * unsettledFrom} are fully ingested.
   *
   * @return whether its checkpoint is too long to keep and the first such in this run
   */
  boolean listed(int page, String checkpoint, int entries, int unsettledFrom) {
    listedEntries += entries;
    boolean tooLong = checkpoint != null && checkpoint.length() > FilePage.MAX_CHECKPOINT_LENGTH;
    String kept = checkpoint == null || tooLong ? null : checkpoint;
    checkpointed |= checkpoint != null;
    unsettled.put(page, new PageMark(page, kept, listedEntries));
    settle(unsettledFrom);
    boolean first = tooLong && !oversized;
    oversized |= tooLong;
    return first;
  }

  /** Keeps of the pages below {@code unsettledFrom} only the last one with a checkpoint. */
  private void settle(int unsettledFrom) {
    while (!unsettled.isEmpty() && unsettled.firstKey() < unsettledFrom) {
      PageMark mark = unsettled.pollFirstEntry().getValue();
      if (mark.checkpoint() != null) {
        settled = mark;
      }
    }
  }

  private void clearPages() {
    unsettled.clear();
    settled = null;
    listedEntries = 0;
    checkpointed = false;
    oversized = false;
  }

  /** The entries the round listed so far, this run's pages included. */
  long entriesListed() {
    long total = 0;
    for (Map.Entry<String, ContainerProgress> entry : containers.entrySet()) {
      if (!entry.getKey().equals(current)) {
        total += entry.getValue().entries();
      }
    }
    return total + entriesBase + listedEntries;
  }

  /** The current container was listed to its last page; kept at once. */
  void completed() {
    ContainerProgress progress = containers.get(current);
    long entries = entriesBase + listedEntries;
    containers.put(
        current,
        new ContainerProgress(
            null,
            progress.restarts(),
            entries,
            progress.resumable() || checkpointed,
            progress.revisitsSeen()));
    state.markScopeCompleted(current);
    if (fromScratch.contains(current)) {
      wholeThisRun.add(current);
    }
    progressed = true;
    current = null;
    clearPages();
    save();
  }

  /** The current container could not be listed: it starts from scratch next time. */
  void unlisted() {
    ContainerProgress progress = containers.get(current);
    containers.put(
        current,
        new ContainerProgress(
            null, progress.restarts(), 0, progress.resumable(), progress.revisitsSeen()));
    current = null;
    clearPages();
    save();
  }

  /**
   * The orderly end of the budget: the current container keeps the last checkpoint of a page below
   * {@code unsettledFrom} - no page from there on is fully ingested - and the round is kept. What
   * the listing first met after that checkpoint ({@code firstMetOn}: path to page) is listed again
   * and is not kept as present.
   *
   * @return whether this run moved the round forward
   */
  boolean budgetSpent(int unsettledFrom, Map<String, Integer> firstMetOn) {
    int relistedFrom = Integer.MAX_VALUE;
    if (current != null) {
      settle(unsettledFrom);
      ContainerProgress progress = containers.get(current);
      relistedFrom = settled == null ? firstPage : settled.page() + 1;
      if (settled != null && !settled.checkpoint().equals(progress.checkpoint())) {
        containers.put(
            current,
            new ContainerProgress(
                settled.checkpoint(),
                progress.restarts(),
                entriesBase + settled.entries(),
                true,
                progress.revisitsSeen()));
        progressed = true;
      }
    }
    Set<String> relisted = new HashSet<>();
    int from = relistedFrom;
    firstMetOn.forEach(
        (path, page) -> {
          if (page >= from) {
            relisted.add(path);
          }
        });
    spansRuns = true;
    save(relisted);
    return progressed;
  }

  /** Whether every container was listed from its first to its last page in this run alone. */
  boolean provenByThisRun() {
    return wholeThisRun.containsAll(keys);
  }

  AbsenceProof proof() {
    return proof;
  }

  /** Whether this run goes on with a round an earlier run began. */
  boolean continuesEarlierRuns() {
    return continued;
  }

  /** Holds {@code cursors} as the start cursors the round's end makes the valid ones. */
  void holdChangeCursors(Map<String, String> cursors) {
    state.holdPendingChangeCursors(cursors);
  }

  /** Whether a container of the round gave a checkpoint, so the next run resumes it. */
  boolean resumes() {
    return containers.values().stream().anyMatch(ContainerProgress::resumable);
  }

  /**
   * The round goes on in a later run - a container could not be listed: the presence this run has
   * seen so far is kept with it.
   */
  void continuesLater() {
    spansRuns = true;
    save();
  }

  /**
   * A container could not be listed although every other one is done: the round ends without a
   * reconciliation or a memory, and the next run starts a new one that lists the others again.
   */
  void abandon() {
    state.discardScan();
    state = journal.saveEnded(state);
  }

  /**
   * Hands the presence of the whole round to the run, so its reconciliation spares it. The
   * attachments of a present parent count as present too.
   */
  void presenceToFrame() {
    save();
    for (String path : journal.presentPaths(state.getId(), scanId)) {
      frame.markPresent(path);
    }
  }

  /**
   * Ends the round: the folder memory is replaced, the full sync completes, and the revisits every
   * container saw at its start are consumed - after the save, or the old memory would stand without
   * its revisit. Without a reconciliation ({@code reconciled} false) a stored document the round
   * did not see may be gone or moved; no folder above one is remembered, so the next round lists
   * it.
   */
  void finish(boolean reconciled) {
    Map<String, Set<UUID>> seen = new LinkedHashMap<>();
    containers.forEach((key, progress) -> seen.put(key, progress.revisitsSeen()));
    Map<String, Set<String>> open = revisits.notedOutside(seen);
    if (!reconciled) {
      journal
          .unseen(state.getId(), scanId, frame.library().getId())
          .forEach((key, paths) -> open.computeIfAbsent(key, k -> new HashSet<>()).addAll(paths));
    }
    state.rememberSubtrees(memory.remembered(open));
    state.completeFullSync(clock.instant());
    state = journal.saveEnded(state);
    List<UUID> consumed = new ArrayList<>();
    seen.values().forEach(consumed::addAll);
    revisits.consume(consumed);
  }

  /**
   * The listing rejected an entry at a storage quota. No change log reports it again after the
   * round, so the round's end leaves the next run a full sync ({@link
   * SourceSyncState#holdFullSyncAtQuota}).
   */
  void heldAtQuota() {
    state.holdFullSyncAtQuota();
  }

  /** Keeps the round as it stands, with the presence this run has seen so far. */
  void save() {
    save(Set.of());
  }

  private void save(Set<String> withheld) {
    state.recordScanProgress(
        new ScanProgress(
            scanId,
            startedAt,
            memory.basis(),
            memory.establishedAt(),
            containers,
            memory.listedMarkers(),
            memory.carriedMarkers(),
            memory.unsettledFolders(),
            proof.name()));
    state = journal.save(state, scanId, frame.library().getId(), newPresence(withheld));
  }

  /**
   * Notes what an event or change run confirmed while a round is open, so the round's end neither
   * removes it nor forgets its folder.
   */
  static void confirm(
      ScanJournal journal, SourceSyncState state, IndexingRun frame, AbsenceProof proof) {
    ScanProgress progress = state.scanProgress();
    if (progress == null
        || frame.currentPaths().isEmpty()
        || (proof == AbsenceProof.SINGLE_RUN
            && progress.containers().values().stream().noneMatch(ContainerProgress::resumable))) {
      return;
    }
    journal.recordPresence(
        state.getId(), progress.scanId(), frame.library().getId(), frame.currentPaths());
  }

  /**
   * The present paths not yet written. Under {@link AbsenceProof#LOCATION_IDENTITY} and {@link
   * AbsenceProof#CHANGE_FEED} at every save - their reconciliation after several runs rests on the
   * presence of every container the round completed, also in a run that failed afterwards. Under
   * {@link AbsenceProof#SINGLE_RUN} once a round that resumes from checkpoints spans runs: until
   * then, and for a store without checkpoints, the run's own listing decides alone.
   */
  private Set<String> newPresence(Set<String> withheld) {
    if (proof == AbsenceProof.SINGLE_RUN && !(spansRuns && resumes())) {
      return Set.of();
    }
    Set<String> fresh = new HashSet<>(frame.currentPaths());
    fresh.removeAll(present);
    fresh.removeAll(withheld);
    present.addAll(fresh);
    return fresh;
  }
}
