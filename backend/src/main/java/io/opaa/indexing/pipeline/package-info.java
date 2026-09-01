/**
 * The format-pipeline abstraction and its shared building blocks
 * (docs/features/ingestion-pipelines.md).
 *
 * <p>A {@link io.opaa.indexing.pipeline.DocumentPipeline} owns reader, splitter, chunk-size and
 * metadata enrichment for one document format, registered as a bean and routed to by {@link
 * io.opaa.indexing.pipeline.DocumentPipelineRegistry} on the format {@link
 * io.opaa.indexing.SupportedDocumentFormats} detected - never on the file extension alone. Each
 * format-specific implementation lives in its own subpackage ({@code pipeline.pdf}, {@code
 * pipeline.office}, {@code pipeline.tabular}, {@code pipeline.mail}, {@code pipeline.html}, {@code
 * pipeline.markdown}); {@link io.opaa.indexing.pipeline.TikaFallbackPipeline} here claims no format
 * and handles everything no specialized pipeline claimed.
 *
 * <p>Every chunk carries its producing pipeline's {@code id}/{@code version} as metadata (see
 * {@link io.opaa.indexing.pipeline.ChunkPipelineMetadata}), read back by {@link
 * io.opaa.indexing.pipeline.PipelineReindexService} to select and selectively re-index every chunk
 * below a given pipeline version. {@code version} is raised only when a pipeline's cut or emitted
 * structure metadata actually changes, never for a behaviour-neutral fix. One selection criterion
 * of {@link io.opaa.indexing.pipeline.PipelineReindexService} is, by necessity, an exception to
 * "never on the file extension alone": catching up a chunk still naming the fallback pipeline whose
 * format a pipeline registered afterwards now claims (#1105) has no other signal available to catch
 * up on - see that class's own Javadoc for the narrower guarantee this approximation gives.
 *
 * <p>A pipeline reads its input through {@link io.opaa.indexing.pipeline.DocumentPipelineSource},
 * which carries either a file or already extracted text. A binary-format pipeline handed a source
 * without a file returns no content rather than failing - a source it cannot read is an empty
 * document, never an error that aborts the run.
 *
 * <p>{@link io.opaa.indexing.pipeline.HeadingSectionSplitter} is the shared section-cutting engine
 * every heading-driven pipeline (Markdown, DOCX, PDF, HTML) uses to turn a flat heading/paragraph
 * event stream into chunks along the heading path in effect at each cut - implemented once here so
 * the empty-section-suppression and soft/hard chunk-size rules cannot drift between formats.
 */
package io.opaa.indexing.pipeline;
