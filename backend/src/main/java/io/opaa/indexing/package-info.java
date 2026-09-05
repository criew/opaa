/**
 * Document indexing and processing for OPAA: job/document orchestration ({@link
 * io.opaa.indexing.FileProcessingService}, {@link io.opaa.indexing.DocumentIndexingService}, {@link
 * io.opaa.indexing.PipelineReindexService}), format detection ({@link
 * io.opaa.indexing.SupportedDocumentFormats}, {@link io.opaa.indexing.DocumentService}), chunking
 * and the vector/full-text stores.
 *
 * <p>This package and {@link io.opaa.indexing.pipeline} depend on each other, each only on the
 * other's stable public contract: the pipeline registry, interface and chunk metadata types here,
 * and the shared core services ({@code SupportedDocumentFormats}, {@code ChunkingService}, {@code
 * DocumentService}, {@code IndexingProperties}) there - never on each other's orchestration.
 */
package io.opaa.indexing;
