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
 *       io.opaa.indexing.format.DocumentFormatRegistry}. It declares what it admits ({@link
 *       io.opaa.indexing.format.FormatAdmission}); {@link
 *       io.opaa.indexing.format.SupportedDocumentFormats} is the union of those declarations and
 *       keeps no list of its own, which is what makes a new format cost one class and one bean.
 *   <li>{@code stream} - a data format only one source delivers, with no extension and no file: it
 *       admits nothing, {@link io.opaa.indexing.format.DocumentFormat#handledFormats()} is
 *       therefore empty, and the source names the format itself through {@code
 *       DocumentIngest.pipelineId}.
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
 * <p>No edge runs the other way inside the subtree: the id the pre-abstraction corpus is attributed
 * to is declared here, in {@link io.opaa.indexing.format.ChunkFormatMetadata}, and {@code
 * file.fallback} points at it - the value is persisted chunk metadata, so it belongs to this
 * package rather than to whichever class implements that format.
 */
package io.opaa.indexing.format;
