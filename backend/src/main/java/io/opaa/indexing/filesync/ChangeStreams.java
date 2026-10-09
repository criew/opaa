package io.opaa.indexing.filesync;

import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.source.IndexingRun;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the change streams of one {@link FileSync} run - a change run, or the end of a round proven
 * by the change log. A reported file goes the run's way at once; a reported removal waits until
 * every stream is read ({@link #applyRemovals()}) and counts only for a document of its stream's
 * containers while they are reachable. The last report of a file is its state: a later report of it
 * as present withdraws an earlier removal, and a stream not read to its last page reports none. The
 * removal itself stays with the run ({@link Sink#remove}).
 */
final class ChangeStreams {

  private static final Logger log = LoggerFactory.getLogger(ChangeStreams.class);

  /** What the reader leaves to its run. */
  interface Sink {

    /** {@code changes} more reported changes, for the run's progress. */
    void counted(int changes);

    /** A reported file that exists and lies inside the patterns. */
    void visit(FileEntry entry) throws InterruptedException;

    /** Removes the document under {@code filePath} with its attachments. */
    void remove(String filePath);

    /** Ingests every download in flight. */
    void drain() throws InterruptedException;

    /** A page reported a change of structure. */
    void structureChanged();

    /** The failures so far that may pass by themselves. */
    int transientFailures();

    /** The entries so far rejected at a storage quota; they come again once there is room. */
    int quotaHolds();
  }

  /** How the read of one stream ended. */
  enum Ending {
    /** Read to its last page. */
    READ,
    /** The source no longer accepts the cursor. */
    EXPIRED,
    /** A page could not be read; the stream stays where it was. */
    FAILED
  }

  /**
   * One stream's read.
   *
   * @param cleanStart the cursor the next read starts at, set only when the stream was read to its
   *     last page with every container reachable and no file failing transiently
   * @param message the source's message when the read did not end {@link Ending#READ}
   * @param quotaHeld whether an entry of this read was rejected at a storage quota: the read still
   *     proves what it reports, but the stream's cursor must not pass that entry
   */
  record StreamRead(Ending ending, String cleanStart, String message, boolean quotaHeld) {}

  /**
   * A removal a stream reported, with the containers it counts for ({@code null} for any) and the
   * read that reported it.
   */
  private record PendingRemoval(String filePath, Set<String> own, int read) {}

  private final IndexingRun frame;
  private final DocumentRepository documentRepository;
  private final Sink sink;
  private final Set<String> changedContainers = new LinkedHashSet<>();
  private final List<PendingRemoval> pendingRemovals = new ArrayList<>();
  private int reads;

  ChangeStreams(IndexingRun frame, DocumentRepository documentRepository, Sink sink) {
    this.frame = frame;
    this.documentRepository = documentRepository;
    this.sink = sink;
  }

  /** Whether every container of a stream can be reached now; each one that cannot is noted. */
  boolean reachable(ChangeFeed feed, List<FileContainer> containers) throws InterruptedException {
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
                    + FileSync.UNLISTABLE_CONTAINER_SUFFIX,
                container.key());
      } catch (FileAccessException e) {
        throw FileSync.runFailure(e);
      }
    }
    return reachable;
  }

  /**
   * Reads {@code feedKey} from {@code cursor} to its last page, applying each change; {@code
   * reachable} tells whether its containers could be reached ({@link #reachable}).
   */
  StreamRead read(
      ChangeFeed feed,
      String feedKey,
      List<FileContainer> containers,
      String cursor,
      boolean reachable)
      throws InterruptedException {
    Set<String> own = new LinkedHashSet<>();
    containers.forEach(container -> own.add(container.key()));
    int read = ++reads;
    int transientBefore = sink.transientFailures();
    int quotaHoldsBefore = sink.quotaHolds();
    String newStart = null;
    String next = cursor;
    try {
      while (next != null) {
        ChangePage page = feed.read(feedKey, next);
        sink.counted(page.changes().size());
        for (Change change : page.changes()) {
          apply(change, reachable, own, read);
          frame.progress().report();
        }
        sink.drain();
        if (page.fullSyncNeeded()) {
          sink.structureChanged();
        }
        next = page.next();
        newStart = page.newStart();
      }
    } catch (FileAccessException.CursorExpired e) {
      log.info(
          "Change cursor of stream {} expired: {}",
          frame.library().loggedNames().of(feedKey),
          frame.library().loggedNames().of(e.getMessage()));
      dropRemovalsOf(read);
      return new StreamRead(Ending.EXPIRED, null, e.getMessage(), false);
    } catch (FileAccessException.RunEnding e) {
      throw FileSync.runFailure(e);
    } catch (FileAccessException e) {
      // the stream stays where it was; the next run reads it again, its removals with it
      dropRemovalsOf(read);
      frame.events().record(IndexingEventCategory.UNREACHABLE, e.getMessage(), feedKey);
      frame.progress().recordFailed();
      return new StreamRead(Ending.FAILED, null, e.getMessage(), false);
    }
    boolean clean = reachable && sink.transientFailures() == transientBefore;
    return new StreamRead(
        Ending.READ, clean ? newStart : null, null, sink.quotaHolds() > quotaHoldsBefore);
  }

  /**
   * One reported change. A removal counts only for a document of {@code own} - another stream
   * reports the files of its containers - and only while those are reachable.
   */
  private void apply(Change change, boolean reachable, Set<String> own, int read)
      throws InterruptedException {
    switch (change) {
      case Change.Removed removed -> {
        if (reachable) {
          // judged once every stream is read: another stream may report the file moved to its area
          pendingRemovals.add(new PendingRemoval(removed.filePath(), own, read));
        } else {
          frame.progress().recordSkipped();
        }
      }
      case Change.Updated updated -> {
        FileEntry entry = updated.entry();
        changedContainers.add(entry.container().key());
        if (entry.exclusion() instanceof Exclusion.Deselected) {
          // outside the patterns now: not part of the bestand; waits with the removals
          pendingRemovals.add(new PendingRemoval(entry.filePath(), null, read));
        } else {
          // the file exists now: an earlier report of its removal is outdated
          pendingRemovals.removeIf(removal -> removal.filePath().equals(entry.filePath()));
          sink.visit(entry);
        }
      }
    }
  }

  /**
   * Drops the removals of a read that did not reach its stream's last page: a later report it did
   * not read may withdraw them, and the stream is read again from its old cursor.
   */
  private void dropRemovalsOf(int read) {
    pendingRemovals.removeIf(removal -> removal.read() == read);
  }

  /**
   * The removals the streams reported, each for a document that still belongs to one of its
   * stream's containers - a document another stream placed elsewhere this run stays.
   */
  void applyRemovals() {
    for (PendingRemoval removal : pendingRemovals) {
      String container =
          documentRepository
              .findByLibraryIdAndFilePath(frame.library().getId(), removal.filePath())
              .map(Document::getSourceContainerKey)
              .orElse(null);
      if (container == null || removal.own() == null || removal.own().contains(container)) {
        if (container != null) {
          changedContainers.add(container);
        }
        sink.remove(removal.filePath());
      } else {
        frame.progress().recordSkipped();
      }
    }
    pendingRemovals.clear();
  }

  /** The containers the read changes added to or removed from. */
  Set<String> changedContainers() {
    return changedContainers;
  }
}
