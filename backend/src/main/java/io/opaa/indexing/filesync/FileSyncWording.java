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

  /** What to change when a full sync spent its budget without storing anything. */
  String budgetStallAdvice();

  /** Where an event run that spent its budget continues, the tail of the budget note. */
  String eventRunContinuation();

  /** The listing part of a full sync's summary, followed by the skip and failure figures. */
  String listedSummary(long listed, long deselected);

  /** The checking part of an event run's summary, followed by the skip and failure figures. */
  String checkedSummary(long checked);

  /** The note of a change run whose stream cannot be read: the next run is a full sync. */
  default String fullSyncFollows() {
    return "Der nächste Lauf ist ein Vollabgleich.";
  }
}
