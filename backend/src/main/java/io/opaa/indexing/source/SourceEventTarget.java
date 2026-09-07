package io.opaa.indexing.source;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.library.KnowledgeLibrary;
import java.util.Set;
import java.util.UUID;

/**
 * What a connector gives the shared {@link SourceEventIntake}: the source type its libraries carry,
 * the executor whose ordinary run takes an overflowed batch, the mode a targeted run is booked
 * under, and the targeted run itself. Authentication and payload parsing stay with the connector;
 * collecting, deferring and dropping are the intake's.
 */
public interface SourceEventTarget {

  /** The source type a library must carry for a batch to be drained; anything else is dropped. */
  DocumentSourceType sourceType();

  /**
   * The executor behind the connector. Its {@link SourceIndexingExecutor#defaultRunMode} decides
   * the mode of an overflowed batch's ordinary run, its {@link SourceIndexingExecutor#execute} runs
   * it.
   */
  SourceIndexingExecutor executor();

  /**
   * The mode a targeted run is booked under - it never lists, so nothing is removed for absence.
   */
  IndexingRunMode targetedRunMode();

  /**
   * Starts the targeted run for exactly {@code keys}, asynchronously like {@link
   * SourceIndexingExecutor#execute}. {@code dropped} counts the reported keys the connector refused
   * before queueing (outside the library's scopes); a connector without that notion receives 0. May
   * throw {@link org.springframework.core.task.TaskRejectedException} when the executor queue is
   * full - the intake then fails the job it just started.
   */
  void refresh(UUID jobId, KnowledgeLibrary library, Set<String> keys, int dropped);
}
