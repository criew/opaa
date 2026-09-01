package io.opaa.indexing;

/**
 * What one {@link PipelineReindexService#reindexBatch} call did. A call that returns {@link
 * #isEmpty()} means the selective re-index has nothing left to do for the requested pipeline and
 * version - the signal to stop calling, the same "0 means drained" contract {@code
 * FullTextBackfillService#backfillBatch} uses.
 *
 * @param reindexedDocuments documents whose source file was locally readable and which were parsed,
 *     chunked and stored again on the spot
 * @param markedForNextRun documents whose source is remote (HTTP directory, RSS feed) and which can
 *     therefore only be re-read by their own connector run - marked so that run redoes them instead
 *     of skipping them as unchanged. Their chunks stay stale until that run happens, which the
 *     progress figures keep showing.
 * @param removedOrphanChunkSets chunk sets whose document row no longer exists at all; deleted
 *     rather than re-indexed, since there is nothing left to re-read
 */
public record PipelineReindexResult(
    int reindexedDocuments, int markedForNextRun, int removedOrphanChunkSets) {

  static final PipelineReindexResult NOTHING_TO_DO = new PipelineReindexResult(0, 0, 0);

  public boolean isEmpty() {
    return reindexedDocuments == 0 && markedForNextRun == 0 && removedOrphanChunkSets == 0;
  }
}
