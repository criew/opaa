package io.opaa.indexing.source;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.library.KnowledgeLibrary;
import java.util.Map;
import java.util.UUID;

/**
 * A run-based way of getting documents into the index (ADR-0017, decision 3). Every implementation
 * declares the single {@link IndexingSourceType} it serves and is registered with the {@link
 * IndexingSourceExecutorRegistry} as a Spring bean - a new source type is added by implementing
 * this interface and wiring one more bean in {@code IndexingConfiguration}, never by editing an
 * existing implementation or the registry itself.
 *
 * <p>{@code targetLibrary} is the only source of configuration (ADR-0018): {@code sourcePath},
 * {@code sourceUrl}, {@code sourceProxy}, {@code sourceCredentials} and {@code sourceInsecureSsl}
 * all live on the library itself, and every executor reads whichever of them its own type carries,
 * ignoring the rest.
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

  /** The source type this executor serves. Used as the registry's lookup key. */
  IndexingSourceType sourceType();

  /**
   * Runs asynchronously and reports progress/completion through {@code IndexingJobService}, the
   * same way every executor has always done. {@code targetLibrary} carries both the destination for
   * every document/chunk this run writes and, since ADR-0018, the run's own quellkonfiguration.
   */
  void execute(UUID jobId, KnowledgeLibrary targetLibrary, IndexingRunMode runMode);
}
