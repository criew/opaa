package io.opaa.indexing.filesync;

import java.util.List;

/**
 * The change log of a store that has one (ADR-0040, Entscheidung 6). A store serves its containers
 * from one or more streams, each read from a cursor the store hands out.
 */
public interface ChangeFeed {

  /** The stream that reports the changes of {@code container}. */
  String feedKey(FileContainer container);

  /**
   * The cursor that reports every change from now on - fetched before a full sync lists anything,
   * so no change during the listing is lost.
   */
  String startCursor(String feedKey) throws FileAccessException, InterruptedException;

  /**
   * One page of changes from {@code cursor}.
   *
   * @throws FileAccessException.CursorExpired when the stream must start over with a full sync
   */
  ChangePage read(String feedKey, String cursor) throws FileAccessException, InterruptedException;

  /**
   * @param next the cursor of the following page, {@code null} on the last one
   * @param newStart the cursor the next run reads from, set on the last page
   * @param fullSyncNeeded a change the stream cannot express per file (a moved or deleted folder):
   *     the next run must be a full sync
   */
  record ChangePage(List<Change> changes, String next, String newStart, boolean fullSyncNeeded) {

    public ChangePage {
      changes = List.copyOf(changes);
    }
  }

  /** One reported change. */
  sealed interface Change {

    /** The file was added or changed, as it is now. */
    record Updated(FileEntry entry) implements Change {}

    /** The file is gone from {@code container} - a deletion finding while it is reachable. */
    record Removed(FileContainer container, String filePath) implements Change {}
  }
}
