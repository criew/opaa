package io.opaa.indexing;

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
  /** A scheduled run was skipped because the library was still indexing. */
  SCHEDULE_SKIPPED,
  /**
   * A previously indexed document no longer exists at its source and was removed at the end of a
   * successful, complete run - see {@link StaleDocumentCleanupService} (#886).
   */
  REMOVED,
  /**
   * The source throttled this run (HTTP 429 with Retry-After) and the run slowed down instead of
   * failing (ADR-0023, #1136) - one summary note per run about how often and how long it waited,
   * not about any single item's outcome.
   */
  RATE_LIMITED,
  /**
   * The run spent its request budget (#1141) and ended in an orderly way as incomplete - one note
   * per run naming the budget and where the next run continues, not an item's outcome.
   */
  BUDGET_EXHAUSTED
}
