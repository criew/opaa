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
 * <p>{@code shared} holds the building blocks both kinds reuse. Outside its own subtree this
 * package uses {@code io.opaa.indexing.chunk} (metadata keys), {@code io.opaa.indexing.metadata}
 * (the reserved key names a format must not claim), {@code io.opaa.indexing.document} (the Tika
 * reader the fallback format parses through) and {@code io.opaa.sourceaccess} (the read ceiling the
 * mail and ODF readers work under). It owns no run and no document state and starts no ingest.
 *
 * <p>Two of those four are mutual, stated rather than claimed away: {@code document} calls this
 * package for every document it takes in, and {@code metadata} both reads {@code
 * DocumentProperties} and re-reads a document through the registry. {@code chunk} and {@code
 * sourceaccess} do not use this package.
 *
 * <p>One edge runs the other way inside the subtree: {@link
 * io.opaa.indexing.format.ChunkFormatMetadata} names {@code file.fallback} for the id the
 * pre-abstraction corpus is attributed to. That value is persisted and belongs to that format, so
 * the cycle is stated rather than broken by copying the literal.
 */
package io.opaa.indexing.format;
