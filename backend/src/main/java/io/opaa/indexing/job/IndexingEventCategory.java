package io.opaa.indexing.job;

/**
 * Why a single item was skipped/rejected during an indexing run, or that the run recorded an error
 * for it. Mirrors {@code IndexingRunEventCategory} in the OpenAPI spec - kept as a separate domain
 * enum (like {@link JobStatus}/{@code IndexingStatus}) rather than a typeMappings-generated one,
 * mapped by hand in {@code LibraryController}, since it is never itself part of a request body.
 */
public enum IndexingEventCategory {
  REJECTED,
  UNREACHABLE,
  UNSUPPORTED_FORMAT,
  ALLOWLIST,
  ERROR,
  /**
   * The item was indexed, but its own file extension did not match its Tika-detected content -
   * never a reason to skip or reinterpret it, only to report the deviation.
   */
  FORMAT_MISMATCH,
  /**
   * The item was indexed without a malware scan: the scanner could not judge it and the policy is
   * {@code ACCEPT} - a note about an accepted item, like {@link #FORMAT_MISMATCH}.
   */
  UNSCANNED,
  /** A scheduled run was skipped because the library was still indexing. */
  SCHEDULE_SKIPPED,
  /**
   * A previously indexed document no longer exists at its source and was removed at the end of a
   * successful, complete run - see {@link
   * io.opaa.indexing.maintenance.StaleDocumentCleanupService}.
   */
  REMOVED,
  /**
   * The source throttled this run (HTTP 429 with Retry-After) and the run slowed down instead of
   * failing (ADR-0023) - one summary note per run about how often and how long it waited, not about
   * any single item's outcome.
   */
  RATE_LIMITED,
  /**
   * The run spent its request budget and ended in an orderly way as incomplete - one note per run
   * naming the budget and where the next run continues, not an item's outcome.
   */
  BUDGET_EXHAUSTED,
  /**
   * The run's own figures at its end - requests, bytes, listed/skipped/processed objects, the time
   * each container took - one note per run for a source that counts them, never an item outcome.
   */
  SUMMARY,
  /**
   * The cleanup after extraction found patterns typical of prompt injection in an indexed document
   * - removed invisible characters, defused role tokens, instruction phrases, URLs with a data
   * placeholder. One note per document with counts per class, never with content; the document was
   * indexed.
   */
  SUSPICIOUS_PATTERNS,
  /**
   * A scanned document was indexed with text read by text recognition (OCR), or cut again from its
   * stored recognition without the service; the message names the connection type and the
   * recipient, never an account.
   */
  TEXT_RECOGNIZED,
  /**
   * Text recognition of a scanned document failed on the service (timeout, key refused, overload,
   * server or connection error); not locked, the next run tries again.
   */
  TEXT_RECOGNITION_FAILED,
  /**
   * Text recognition of a scanned document is locked: the service refused this document, or it
   * exceeds the page limit; it is not sent again until file or procedure change or the lock is
   * released.
   */
  TEXT_RECOGNITION_BLOCKED,
  /** A scanned document waits for text recognition: the page budget of this run is spent. */
  TEXT_RECOGNITION_PENDING
}
