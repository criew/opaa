package io.opaa.indexing.source;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.knowledge.KnowledgeLibrary;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The run mode nobody requested: the executor's own default, except that a library whose stored
 * sync state was written under other settings runs {@link IndexingRunMode#FULL} wherever its
 * executor knows that mode - the state's anchor or cursors mean nothing under the current settings.
 */
public class DefaultRunModes {

  private static final Logger log = LoggerFactory.getLogger(DefaultRunModes.class);

  private final SourceConnectionResolver connectionResolver;
  private final SourceSyncStateRepository states;
  private final SyncStateBasis basis;

  public DefaultRunModes(
      SourceConnectionResolver connectionResolver,
      SourceSyncStateRepository states,
      SyncStateBasis basis) {
    this.connectionResolver = connectionResolver;
    this.states = states;
    this.basis = basis;
  }

  /** The mode {@code library}'s next run takes when none was requested. */
  public IndexingRunMode of(SourceIndexingExecutor executor, KnowledgeLibrary library) {
    IndexingRunMode mode =
        executor.defaultRunMode(library, connectionResolver.effectiveSettings(library));
    if (mode == IndexingRunMode.FULL
        || !executor.runModes().containsKey(IndexingRunMode.FULL)
        || !writtenUnderOtherSettings(library)) {
      return mode;
    }
    log.info(
        "Library {} holds a sync state of other settings - its next run is a full one",
        library.getId());
    return IndexingRunMode.FULL;
  }

  /** A settings read that fails leaves the executor's choice; the run itself discards the state. */
  private boolean writtenUnderOtherSettings(KnowledgeLibrary library) {
    try {
      return states
          .findByLibraryId(library.getId())
          .map(
              state ->
                  !Objects.equals(
                      state.getSettingsBasis(), basis.current(library, connectionResolver)))
          .orElse(false);
    } catch (RuntimeException e) {
      log.warn(
          "Could not compare the sync state of library {} with its settings",
          library.getId(),
          library.loggedNames().of(e));
      return false;
    }
  }
}
