package io.opaa.indexing;

/**
 * What one {@link ContextPrefixRerunService#rerunBatch} call did. {@link #isEmpty()} is the signal
 * to stop calling - the same "advanced nothing means drained" contract {@code
 * MetadataBackfillResult} uses, and it ignores {@link #skippedDocuments()} for the same reason: a
 * call that only skipped would retry the same unreachable documents forever.
 *
 * @param processedDocuments documents re-embedded under the current Kontextpraefix: their chunks
 *     were rewritten in place under their own ids and the prefix version was recorded
 * @param skippedDocuments documents this call could not advance; nothing about them changed, they
 *     keep their old chunks and stay pending
 */
public record ContextPrefixRerunResult(int processedDocuments, int skippedDocuments) {

  static final ContextPrefixRerunResult NOTHING_TO_DO = new ContextPrefixRerunResult(0, 0);

  public boolean isEmpty() {
    return processedDocuments == 0;
  }
}
