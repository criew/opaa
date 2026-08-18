package io.opaa.indexing;

import io.opaa.api.dto.IndexingTriggerRequest;
import io.opaa.library.KnowledgeLibrary;
import java.util.UUID;

/**
 * A run-based way of getting documents into the index (ADR-0017, decision 3). Every implementation
 * declares the single {@link IndexingSourceType} it serves and is registered with the {@link
 * IndexingSourceExecutorRegistry} as a Spring bean - a new source type is added by implementing
 * this interface and wiring one more bean in {@code IndexingConfiguration}, never by editing an
 * existing implementation or the registry itself.
 */
public interface SourceIndexingExecutor {

  /** The source type this executor serves. Used as the registry's lookup key. */
  IndexingSourceType sourceType();

  /**
   * Runs asynchronously and reports progress/completion through {@code IndexingJobService}, the
   * same way every executor has always done. {@code request} carries whatever type-specific fields
   * this executor needs (e.g. {@code url}/{@code proxy}/{@code credentials} for {@link
   * IndexingSourceType#HTTP_DIRECTORY}); an executor that needs none of them (e.g. {@link
   * IndexingSourceType#FILESYSTEM}, which reads {@code IndexingProperties} instead) simply ignores
   * them.
   */
  void execute(UUID jobId, IndexingTriggerRequest request, KnowledgeLibrary targetLibrary);
}
