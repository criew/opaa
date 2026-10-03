package io.opaa.indexing.filesync;

import java.util.List;

/**
 * One page of a stream's changes.
 *
 * @param next the cursor of the following page, {@code null} on the last one
 * @param newStart the cursor the next run reads from, set on the last page only
 * @param fullSyncNeeded the stream reported a change of structure (a folder moved, renamed or
 *     deleted) that only a full sync reconciles
 */
public record ChangePage(
    List<Change> changes, String next, String newStart, boolean fullSyncNeeded) {

  public ChangePage {
    changes = List.copyOf(changes);
    if ((next == null) == (newStart == null)) {
      throw new IllegalArgumentException("a page names either the next page or the new start");
    }
  }
}
