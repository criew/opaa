package io.opaa.indexing;

/**
 * Why a single item was skipped/rejected during an indexing run, or that the run recorded an error
 * for it (#513). Mirrors {@code IndexingRunEventCategory} in the OpenAPI spec - kept as a separate
 * domain enum (like {@link JobStatus}/{@code IndexingStatus}) rather than a typeMappings-generated
 * one, mapped by hand in {@code LibraryController}, since it is never itself part of a request
 * body.
 */
public enum IndexingEventCategory {
  REJECTED,
  UNREACHABLE,
  UNSUPPORTED_FORMAT,
  ALLOWLIST,
  ERROR,
  /** A scheduled run (#485) was skipped because the library was still indexing. */
  SCHEDULE_SKIPPED
}
