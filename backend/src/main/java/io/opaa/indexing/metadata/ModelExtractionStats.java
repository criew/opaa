package io.opaa.indexing.metadata;

import java.time.Instant;
import java.util.UUID;

/**
 * The Zählwerk of the model-backed extraction of one library (metadata-schema.md, "Die Zahl der
 * Extraktionsaufrufe wird je Bibliothek geführt"). Without it, the only feedback about the cost of
 * this capability is the model provider's invoice - and the only feedback about the threshold is
 * the absence of values nobody can explain.
 */
public record ModelExtractionStats(
    UUID libraryId,
    long calls,
    long acceptedValues,
    long rejectedBelowThreshold,
    long rejectedOutsideVocabulary,
    long failures,
    long rejectedPoolFull,
    long keywordsAssigned,
    Instant lastCallAt) {

  public static ModelExtractionStats empty(UUID libraryId) {
    return new ModelExtractionStats(libraryId, 0, 0, 0, 0, 0, 0, 0, null);
  }
}
