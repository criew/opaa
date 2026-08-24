package io.opaa.indexing;

public enum FileProcessingResult {
  PROCESSED,
  SKIPPED,

  /**
   * The target library's storage quota would be exceeded by this document - nothing was persisted,
   * no row and no chunks. Deliberately distinct from {@link #SKIPPED} (which covers "unchanged,
   * already indexed" and "row deleted concurrently"): a caller must report this one as a rejection,
   * not silence it, so an operator can see why a library's bestand stopped growing.
   */
  QUOTA_EXCEEDED
}
