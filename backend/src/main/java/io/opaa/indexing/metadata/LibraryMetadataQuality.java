package io.opaa.indexing.metadata;

import java.util.List;
import java.util.UUID;

/**
 * The Extraktionsgüte of one library: per field where its values came from, plus the Zählwerk of
 * the model-backed extraction. Counted at query time in the rights context of the asking person,
 * never precomputed (metadata-schema.md, Rechte-Invariante).
 */
public record LibraryMetadataQuality(
    UUID libraryId,
    long totalDocuments,
    boolean modelExtractionEnabled,
    boolean keywordsEnabled,
    double confidenceThreshold,
    List<MetadataFieldQuality> fields,
    ModelExtractionStats modelExtraction) {}
