package io.opaa.indexing.maintenance;

import io.opaa.indexing.chunk.EmbeddingIdentity;
import io.opaa.indexing.chunk.EmbeddingIndexRecord;

/**
 * The vector index and its re-embedding as the administration sees them, installation-wide and
 * without any content: identities, the last run, and how many chunks already carry a vector of the
 * configured model. Chunks of private libraries count only where their owners reach the minimum
 * group size ({@code PersonThreshold}, ADR-0041); otherwise no number tells they exist.
 *
 * @param current the model the vector search runs on
 * @param configured the model the configuration names
 * @param stale whether the two differ; the vector search is suspended while they do
 * @param running whether a run is working in this process right now
 * @param totalChunks the chunks counted, see {@code privateLibrariesCounted}
 * @param embeddedChunks of those, the chunks with a vector of the configured model
 * @param privateLibrariesCounted whether the counts include the chunks of private libraries
 */
public record EmbeddingReindexProgress(
    EmbeddingIdentity current,
    EmbeddingIdentity configured,
    boolean stale,
    boolean running,
    EmbeddingIndexRecord.Run lastRun,
    long totalChunks,
    long embeddedChunks,
    boolean privateLibrariesCounted) {}
