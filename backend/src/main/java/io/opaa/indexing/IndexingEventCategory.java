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
  SCHEDULE_SKIPPED
}
