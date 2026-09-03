package io.opaa.indexing.source;

/**
 * What a run mode's absence evidence is worth (ADR-0017, Entscheidung 5; refined per run mode by
 * ADR-0023, Entscheidung 4). Every {@link SourceIndexingExecutor} declares one policy per supported
 * {@link io.opaa.api.types.IndexingRunMode}; {@code StaleDocumentCleanupService} refuses a cleanup
 * call whose run mode is not {@link #REMOVE_ON_ABSENCE}.
 */
public enum VanishedDocumentPolicy {
  /**
   * The run listed the source completely ("vollständig auflistend"): a previously indexed document
   * missing from the listing no longer exists at the source and is removed.
   */
  REMOVE_ON_ABSENCE,
  /**
   * The run only saw a window of the source ("ergänzend"): absence from this run's items is no
   * evidence at all, nothing is ever removed for it.
   */
  KEEP_ON_ABSENCE
}
