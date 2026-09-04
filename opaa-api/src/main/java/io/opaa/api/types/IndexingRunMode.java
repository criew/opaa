package io.opaa.api.types;

/**
 * The Betriebsart of one indexing run (ADR-0023, Entscheidung 4 - a refinement of ADR-0017,
 * Entscheidung 5): whether this run listed its source completely, so that absence from its listing
 * is evidence a document is gone, or only picked up what changed since an anchor, where absence
 * proves nothing. Declared per run on {@code indexing_jobs.run_mode}, and per executor as the set
 * of modes it supports together with the deletion policy of each (no implicit default).
 */
public enum IndexingRunMode {
  /**
   * A complete listing of the source ("vollständig auflistend"): everything the source holds is
   * enumerated, a previously indexed document missing from the listing is removed. The only mode
   * FILESYSTEM and HTTP_DIRECTORY know; for CONFLUENCE the first run, every run after a change of
   * the space selection, and a regular full reconciliation.
   */
  FULL,
  /**
   * Only what changed since the last anchor ("ergänzend"): nothing is ever removed for being absent
   * from this run's window. The only mode RSS_FEED knows; for CONFLUENCE the routine run between
   * two full reconciliations.
   */
  INCREMENTAL
}
