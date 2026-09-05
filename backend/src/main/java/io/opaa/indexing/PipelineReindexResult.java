package io.opaa.indexing;

/**
 * What one {@link PipelineReindexService#reindexBatch} call did. A call that returns {@link
 * #isEmpty()} means the selective re-index has nothing left it can do for the requested pipeline
 * and version - the signal to stop calling (a "0 means drained" contract).
 *
 * @param reindexedDocuments documents whose source file was readable within the directories this
 *     deployment is configured to read, and which were parsed, chunked and stored again on the spot
 * @param markedForNextRun documents whose source is remote (HTTP directory, RSS feed) and which can
 *     therefore only be re-read by their own connector run - marked so that run redoes them instead
 *     of skipping them as unchanged. Their chunks stay stale until that run happens, which the
 *     progress figures keep showing.
 * @param skippedDocuments documents this call could not advance: their file lies outside what this
 *     deployment may read (an allowlist narrowed since indexing, a path that no longer resolves
 *     underneath its library's configured directory), the pipeline failed to produce chunks for
 *     them this time, or the document was re-indexed but its chunks still name the fallback
 *     pipeline - re-selecting it under the same {@code pipelineId} would never converge (loop
 *     protection), so it is counted here instead of as {@link #reindexedDocuments()}. For the first
 *     two causes, nothing about the document is changed - in particular a working document keeps
 *     its existing chunks rather than being emptied. The third cause has already read the file,
 *     replaced the chunks and paid for new embeddings; a persistent case of it (a document whose
 *     format claims a specialized pipeline but whose content keeps routing to the fallback) is
 *     re-parsed and re-embedded on every call that requests that pipeline, since {@code
 *     reindexBatch} always starts scanning from offset zero and the stable {@code ORDER BY}
 *     resurfaces it first. Regardless of cause, their chunks stay visible as outstanding in {@link
 *     PipelineReindexService#progressForOrganization}
 * @param removedOrphanChunkSets chunk sets whose document row no longer exists at all; deleted
 *     rather than re-indexed, since there is nothing left to re-read
 */
public record PipelineReindexResult(
    int reindexedDocuments,
    int markedForNextRun,
    int skippedDocuments,
    int removedOrphanChunkSets) {

  static final PipelineReindexResult NOTHING_TO_DO = new PipelineReindexResult(0, 0, 0, 0);

  /**
   * Whether this call advanced nothing. Deliberately ignores {@link #skippedDocuments()}: a call
   * that only skipped is exactly the "nothing further is possible right now" state a caller must
   * stop on - repeating it would retry the same unreachable documents forever.
   */
  public boolean isEmpty() {
    return reindexedDocuments == 0 && markedForNextRun == 0 && removedOrphanChunkSets == 0;
  }
}
