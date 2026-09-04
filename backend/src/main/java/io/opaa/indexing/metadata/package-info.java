/**
 * The document metadata schema (docs/features/metadata-schema.md, ADR-0024): the three core fields
 * Titel, Dokumentart and Datum/Stand, stored per document with the origin of every value, plus the
 * deterministic extraction that fills them from what a {@link
 * io.opaa.indexing.pipeline.DocumentPipeline} declares in {@link
 * io.opaa.indexing.pipeline.DocumentProperties}. Interpretation happens only in {@link
 * io.opaa.indexing.metadata.CoreMetadataExtractor}; the pipelines hand over raw sources and never
 * guess.
 */
package io.opaa.indexing.metadata;
