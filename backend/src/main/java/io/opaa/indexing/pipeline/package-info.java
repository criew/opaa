/**
 * The format-pipeline abstraction and its shared building blocks
 * (docs/features/ingestion-pipelines.md).
 *
 * <p>A {@link io.opaa.indexing.pipeline.DocumentPipeline} owns reader, splitter, chunk-size and
 * metadata enrichment for one document format, registered as a bean and routed to by {@link
 * io.opaa.indexing.pipeline.DocumentPipelineRegistry} on the format {@link
 * io.opaa.indexing.SupportedDocumentFormats} detected - never on the file extension alone. Each
 * format-specific implementation lives in its own subpackage; {@link
 * io.opaa.indexing.pipeline.TikaFallbackPipeline} claims no format and handles everything no
 * specialized pipeline claimed.
 *
 * <p>Every chunk carries its producing pipeline's {@code id}/{@code version} (see {@link
 * io.opaa.indexing.pipeline.ChunkPipelineMetadata}), which {@code
 * io.opaa.indexing.PipelineReindexService} reads back to select and selectively re-index. {@code
 * version} is raised only when a pipeline's cut or emitted structure metadata changes, never for a
 * behaviour-neutral fix.
 *
 * <p>A pipeline reads its input through {@link io.opaa.indexing.pipeline.DocumentPipelineSource},
 * which carries either a file or already extracted text. A binary-format pipeline handed a source
 * without a file returns no content rather than failing.
 *
 * <p>{@link io.opaa.indexing.pipeline.HeadingSectionSplitter} is the shared section-cutting engine
 * every heading-driven pipeline uses, implemented once here so the empty-section suppression and
 * the soft/hard chunk-size rules cannot drift between formats.
 *
 * <p><b>This package holds no job/document orchestration and never calls back into it.</b> It uses
 * core services from {@code io.opaa.indexing} ({@code SupportedDocumentFormats}, {@code
 * ChunkingService}, {@code DocumentService}, {@code IndexingProperties}), and the core in turn
 * consumes this package's registry, interface and metadata keys - a one-directional dependency on a
 * public contract, not a cycle.
 */
package io.opaa.indexing.pipeline;
