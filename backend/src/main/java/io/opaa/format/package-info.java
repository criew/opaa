/**
 * How a document is turned into chunks, per format (ingestion-pipelines.md). A {@link
 * io.opaa.format.DocumentFormat} owns reader, splitter, chunk size and metadata enrichment for one
 * format; every chunk carries its {@code id}/{@code version} ({@link
 * io.opaa.format.ChunkFormatMetadata}), raised only when a cut changes. Both are persisted on every
 * chunk, so neither is a display name.
 *
 * <p>Two kinds of format, one contract, kept apart by subpackage:
 *
 * <ul>
 *   <li>{@code file} - a file format, reached over its detected content by {@link
 *       io.opaa.format.DocumentFormatRegistry}. It declares what it admits ({@link
 *       io.opaa.format.FormatAdmission}); {@link io.opaa.format.SupportedDocumentFormats} is the
 *       union of those declarations and keeps no list of its own, which is what makes a new format
 *       cost one class and one bean.
 *   <li>{@code stream} - a data format only one source delivers, with no extension and no file: it
 *       admits nothing, {@link io.opaa.format.DocumentFormat#handledFormats()} is therefore empty,
 *       and the source names the format itself through {@code DocumentIngest.pipelineId}.
 * </ul>
 *
 * <p>{@code shared} holds the building blocks both kinds reuse, {@code chunk} the generic token cut
 * with its Fundort and the chunk metadata key every format writes. {@link
 * io.opaa.format.DocumentService} reads a file through Tika for the fallback format and classifies
 * a directory by admission. The package knows only {@code io.opaa.sourceaccess} (the read ceiling
 * the mail and ODF readers work under) and nothing of indexing, knowledge or library: it owns no
 * run and no document state and starts no ingest. The chunk size arrives through {@link
 * io.opaa.format.chunk.ChunkSizing}, which the application's configuration implements.
 *
 * <p>No edge runs the other way inside the subtree: the id the pre-abstraction corpus is attributed
 * to is declared here, in {@link io.opaa.format.ChunkFormatMetadata}, and {@code file.fallback}
 * points at it - the value is persisted chunk metadata, so it belongs to this package rather than
 * to whichever class implements that format.
 */
package io.opaa.format;
