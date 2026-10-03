package io.opaa.indexing.filesync;

/** The protocol sentences of a {@link FileSync} that name the connector's own terms. */
public interface FileSyncWording {

  /** An entry whose listed size exceeds {@code maxBytes}, skipped before any download. */
  String tooLarge(FileEntry entry, long maxBytes);

  /** The run failure once the containers list more than {@code maxEntries} entries. */
  String tooManyEntries(long maxEntries);

  /** The {@code REMOVED} entry of a file the source confirmed gone in an event run. */
  String goneConfirmed();

  /** The note after the count of reported references the connector dropped before the run. */
  String droppedReferencesNote();
}
