/**
 * The document metadata schema (docs/features/metadata-schema.md, ADR-0024): the three core fields
 * Titel, Dokumentart and Datum/Stand, stored per document with the origin of every value, plus the
 * deterministic extraction that fills them from what a {@link io.opaa.format.DocumentFormat}
 * declares in {@link io.opaa.format.DocumentProperties}. Interpretation happens only in {@link
 * io.opaa.metadata.CoreMetadataExtractor}; the pipelines hand over raw sources and never guess.
 *
 * <p>Also here: the library fields, the Dokumentart vocabulary, the model step, corrections, the
 * metadata filter and the Kontextpräfix a chunk is embedded behind. Sits above {@code knowledge}
 * and below {@code indexing}, whose ingest and passes over the stock call into it; it names no
 * pipeline class. What it needs from there it declares as an interface the index implements ({@link
 * io.opaa.metadata.ChunkMetadataStore}, {@link io.opaa.metadata.ContextPrefixBacklog}).
 */
package io.opaa.metadata;
