package io.opaa.indexing.source;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A run-based way of getting documents into the index (ADR-0017, decision 3). Every implementation
 * declares the single {@link SourceType} it serves and is registered with the {@link
 * IndexingSourceExecutorRegistry} as a Spring bean - a new source type is added by implementing
 * this interface and declaring the bean in the connector package's own {@code @Configuration},
 * never by editing the indexing core, an existing implementation or the registry itself.
 *
 * <p>An executor takes target, secret and settings from the {@link IndexingRun} its frame hands
 * over ({@link IndexingRun#settings()}, {@link IndexingRun#currentCredentials()}), never from the
 * library (ADR-0041, Entscheidung 3a).
 */
public interface SourceIndexingExecutor {

  /**
   * The run modes this executor supports, each with the policy its absence evidence carries
   * (ADR-0023, Entscheidung 4). Explicit registration, no implicit default: {@code
   * DocumentIndexingService} rejects a requested mode that is not a key here, and {@code
   * StaleDocumentCleanupService} rejects a cleanup call from a mode whose policy is not {@link
   * VanishedDocumentPolicy#REMOVE_ON_ABSENCE}.
   */
  Map<IndexingRunMode, VanishedDocumentPolicy> runModes();

  /**
   * The mode a run of {@code library} takes when none is requested (scheduler, plain "Jetzt
   * indizieren"). A one-mode executor has nothing to decide; an executor with several modes must
   * override this and decide from the library's own state and its stored connector {@code settings}
   * (ADR-0023, Entscheidung 4: the first run and every run after a selection change are full ones,
   * a full reconciliation stays due regularly).
   */
  default IndexingRunMode defaultRunMode(KnowledgeLibrary library, ConnectorData settings) {
    Set<IndexingRunMode> modes = runModes().keySet();
    if (modes.size() != 1) {
      throw new IllegalStateException(
          sourceType() + " declares " + modes + " and must override defaultRunMode");
    }
    return modes.iterator().next();
  }

  /** The source type this executor serves. Used as the registry's lookup key. */
  SourceType sourceType();

  /**
   * Runs asynchronously and reports progress/completion through {@code IndexingJobService}, the
   * same way every executor has always done. {@code targetLibrary} is the destination for every
   * document/chunk this run writes.
   */
  void execute(UUID jobId, KnowledgeLibrary targetLibrary, IndexingRunMode runMode);
}
