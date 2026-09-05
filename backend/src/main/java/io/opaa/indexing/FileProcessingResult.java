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
  QUOTA_EXCEEDED,

  /**
   * The document carried no extractable text - very likely a scan PDF without a text layer
   * (ingestion-pipelines.md, Teil 3, Punkt 1). The document row was already created and is marked
   * {@code FAILED} with a German, user-facing message instead of being indexed with zero chunks; a
   * caller must report this as a rejection, the same way it already reports {@link
   * #QUOTA_EXCEEDED}.
   */
  NO_EXTRACTABLE_TEXT,

  /**
   * A pipeline produced no chunks and did not reject the document as text-free: it either could not
   * read the source at all ({@code PARSE_FAILED}) or read it and found it empty ({@code
   * NO_CONTENT}). The row is marked {@code FAILED} either way, and a caller must report it like an
   * uncaught exception from the same run - an {@code ERROR} event and a failed-document count.
   * Distinct from {@link #NO_EXTRACTABLE_TEXT}, which is a rejection after a successful parse.
   */
  FAILED
}
