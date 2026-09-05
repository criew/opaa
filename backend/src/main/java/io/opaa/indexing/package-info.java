/**
 * Document indexing and processing for OPAA: job/document orchestration ({@link
 * io.opaa.indexing.FileProcessingService}, {@link io.opaa.indexing.DocumentIndexingService}, {@link
 * io.opaa.indexing.PipelineReindexService}), format detection ({@link
 * io.opaa.indexing.SupportedDocumentFormats}, {@link io.opaa.indexing.DocumentService}), chunking
 * and the vector/full-text stores.
 *
 * <p>This package consumes {@link io.opaa.indexing.pipeline}'s registry, its {@code
 * DocumentPipeline} interface and its chunk metadata types ({@code DocumentPipelineRegistry} routes
 * a file to its {@code DocumentPipeline}; {@link io.opaa.indexing.PipelineReindexService} reads
 * {@code ChunkPipelineMetadata}). {@code io.opaa.indexing.pipeline} in turn consumes core services
 * from here that every pipeline implementation needs - {@code SupportedDocumentFormats}, {@code
 * ChunkingService}, {@code DocumentService}, {@code IndexingProperties} - so the two packages
 * depend on each other, each on the other's stable, public contract (interface, registry, metadata
 * keys, shared services), not on each other's orchestration.
 */
package io.opaa.indexing;
