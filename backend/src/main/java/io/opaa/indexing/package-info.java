/**
 * Document indexing and processing for OPAA: job/document orchestration ({@link
 * io.opaa.indexing.FileProcessingService}, {@link io.opaa.indexing.DocumentIndexingService}, {@link
 * io.opaa.indexing.PipelineReindexService}), format detection ({@link
 * io.opaa.indexing.SupportedDocumentFormats}, {@link io.opaa.indexing.DocumentService}), chunking
 * and the vector/full-text stores.
 *
 * <p>This package depends on {@link io.opaa.indexing.pipeline} for the format-pipeline abstraction
 * ({@code DocumentPipelineRegistry} routes a file to its {@code DocumentPipeline}) - never the
 * other way around (#1117): {@code io.opaa.indexing.pipeline} is a leaf package, so nothing that
 * belongs to job/document orchestration lives there merely because it also touches pipeline
 * metadata. {@link io.opaa.indexing.PipelineReindexService} is the concrete example - it selects
 * documents, triggers re-indexing through {@link io.opaa.indexing.FileProcessingService} and
 * reports progress, all orchestration concerns, even though it reads {@code
 * io.opaa.indexing.pipeline.ChunkPipelineMetadata} to do so.
 */
package io.opaa.indexing;
