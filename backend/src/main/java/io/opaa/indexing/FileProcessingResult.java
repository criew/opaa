package io.opaa.indexing;

public enum FileProcessingResult {
  PROCESSED,
  SKIPPED,

  /**
   * The target library's storage quota (#119) would be exceeded by this document - nothing was
   * persisted, no row and no chunks. Deliberately distinct from {@link #SKIPPED} (which covers
   * "unchanged, already indexed" and "row deleted concurrently"): a caller must report this one as
   * a rejection, not silence it the way an unchanged-content skip is, so an operator can see why a
   * library's bestand stopped growing. See each executor's handling of this value for the resulting
   * {@code IndexingEventCategory#REJECTED} run protocol event (#604).
   */
  QUOTA_EXCEEDED
}
