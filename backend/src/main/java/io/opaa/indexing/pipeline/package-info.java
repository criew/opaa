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
 * {@link io.opaa.indexing.pipeline.ChunkPipelineMetadata}), read back by {@code
 * io.opaa.indexing.PipelineReindexService} to select and selectively re-index every chunk below a
 * given pipeline version. {@code version} is raised only when a pipeline's cut or emitted structure
 * metadata actually changes, never for a behaviour-neutral fix. One selection criterion of that
 * class is, by necessity, an exception to "never on the file extension alone": catching up a chunk
 * still naming the fallback pipeline whose format a pipeline registered afterwards now claims
 * (#1105) has no other signal available to catch up on - see that class's own Javadoc for the
 * narrower guarantee this approximation gives.
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
 *
 * <p><b>This package holds no job/document orchestration and never calls back into it (#1117).</b>
 * It uses core services from {@code io.opaa.indexing} it needs to do its own job - format
 * detection, chunking, document parsing ({@code SupportedDocumentFormats}, {@code ChunkingService},
 * {@code DocumentService}, {@code IndexingProperties}) - and the core in turn consumes this
 * package's registry, its {@link io.opaa.indexing.pipeline.DocumentPipeline} interface and its
 * chunk metadata types ({@link io.opaa.indexing.pipeline.ChunkPipelineMetadata}). That is a normal,
 * one-directional dependency of the core on this package's public contract, not a cycle. What moved
 * out to {@code io.opaa.indexing} is only the orchestration that used to sit here despite not being
 * a formatting decision: {@code PipelineReindexService}/{@code PipelineReindexResult}/{@code
 * PipelineVersionProgress} select documents, trigger re-indexing through {@code
 * FileProcessingService} and report progress - the one call from this package back into {@code
 * FileProcessingService} that existed before #1117 is gone, and no pipeline implementation here
 * calls into job/document orchestration. A pipeline-specific helper that only one pipeline needs
 * (see {@link io.opaa.indexing.pipeline.TikaFallbackPipeline#isTextlessPdf}) stays package-private
 * here rather than being exposed on an {@code io.opaa.indexing} service for this package alone to
 * call.
 */
package io.opaa.indexing.pipeline;
