package io.opaa.indexing;

import java.util.UUID;

/**
 * The pipeline-version fill state of one knowledge library (docs/features/ingestion-pipelines.md,
 * cross-cutting rule (d): resumable, with progress queryable per library).
 *
 * @param totalChunks every chunk of this library in the vector store
 * @param currentVersionChunks chunks produced by the current version of the pipeline named on them
 * @param staleChunks chunks below the current version of the pipeline named on them, including the
 *     pre-abstraction corpus (see {@link ChunkPipelineMetadata#LEGACY_PIPELINE_VERSION}). Counted
 *     directly rather than derived as {@code totalChunks - currentVersionChunks}: a chunk naming a
 *     pipeline that is no longer registered at all is neither current nor stale - it cannot be
 *     re-indexed by any pipeline this deployment has - and must not silently inflate either count.
 */
public record PipelineVersionProgress(
    UUID libraryId, long totalChunks, long currentVersionChunks, long staleChunks) {

  /** Whether nothing in this library is waiting for a pipeline-version re-index. */
  public boolean isComplete() {
    return staleChunks == 0;
  }
}
