package io.opaa.indexing.metadata;

import io.opaa.indexing.EmbeddingRateEstimator;

/**
 * The Folgekosten of one planned schema change (metadata-schema.md, Kostentabelle): what it touches
 * and what it costs. Concrete numbers a decision can be made on, never a general warning.
 *
 * @param affectedChunks 0 unless the change is prefix-effective - a field that only filters or only
 *     appears in the Beleg changes no chunk
 * @param embeddingCalls one per chunk, so equal to {@code affectedChunks} or 0
 */
public record MetadataChangeImpact(
    long affectedDocuments,
    long affectedChunks,
    long embeddingCalls,
    long estimatedSeconds,
    boolean reembeddingRequired,
    EmbeddingRateEstimator.RateSource rateSource) {

  public static MetadataChangeImpact free(EmbeddingRateEstimator.RateSource rateSource) {
    return new MetadataChangeImpact(0, 0, 0, 0, false, rateSource);
  }
}
