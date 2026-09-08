/**
 * How a document is turned into chunks, per format (ingestion-pipelines.md). A {@link
 * io.opaa.indexing.format.DocumentFormat} owns reader, splitter, chunk size and metadata enrichment
 * for one format; every chunk carries its {@code id}/{@code version} ({@link
 * io.opaa.indexing.format.ChunkFormatMetadata}), raised only when a cut changes. Both are persisted
 * on every chunk, so neither is a display name.
 *
 * <p>Two kinds of format, one contract, kept apart by subpackage:
 *
 * <ul>
 *   <li>{@code file} - a file format, reached over its detected content by {@link
 *       io.opaa.indexing.format.DocumentFormatRegistry} and admitted by {@link
 *       io.opaa.indexing.format.SupportedDocumentFormats}.
 *   <li>{@code stream} - a data format only one source delivers, with no extension and no file:
 *       {@link io.opaa.indexing.format.DocumentFormat#handledFormats()} is empty and the source
 *       names the format itself through {@code DocumentIngest.pipelineId}.
 * </ul>
 *
 * <p>{@code shared} holds the building blocks both kinds reuse. This package holds no job or
 * document orchestration and never calls back into it; outside its own subtree it uses only {@code
 * io.opaa.indexing.chunk} (metadata keys) and {@code io.opaa.indexing.metadata} (the reserved key
 * names a format must not claim), neither of which uses it.
 *
 * <p>One edge runs the other way inside the subtree: {@link
 * io.opaa.indexing.format.ChunkFormatMetadata} names {@code file.fallback} for the id the
 * pre-abstraction corpus is attributed to. That value is persisted and belongs to that format, so
 * the cycle is stated rather than broken by copying the literal.
 */
package io.opaa.indexing.format;
