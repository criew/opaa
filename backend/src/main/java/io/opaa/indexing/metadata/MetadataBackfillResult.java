package io.opaa.indexing.metadata;

/**
 * What one {@link MetadataBackfillService#backfillBatch} call did. {@link #isEmpty()} is the signal
 * to stop calling - the same "advanced nothing means drained" contract {@code
 * PipelineReindexResult} uses, and for the same reason it ignores {@link #skippedDocuments()}: a
 * call that only skipped would retry the same unreachable documents forever.
 *
 * @param processedDocuments documents re-extracted on the spot: values stored, chunk keys
 *     rewritten, extraction version recorded - one transaction each
 * @param markedForNextRun remote documents marked for their next connector run, which re-extracts
 *     on every inflow; they stay pending until that run happens
 * @param skippedDocuments documents this call could not advance (file unreadable or vanished,
 *     re-extraction failed); nothing about them changed, they stay pending and are retried next
 *     call
 */
public record MetadataBackfillResult(
    int processedDocuments, int markedForNextRun, int skippedDocuments) {

  static final MetadataBackfillResult NOTHING_TO_DO = new MetadataBackfillResult(0, 0, 0);

  public boolean isEmpty() {
    return processedDocuments == 0 && markedForNextRun == 0;
  }
}
