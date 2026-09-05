/**
 * The format-pipeline abstraction and its shared building blocks (ingestion-pipelines.md).
 *
 * <p>A {@link io.opaa.indexing.pipeline.DocumentPipeline} owns reader, splitter, chunk size and
 * metadata enrichment for one format, registered as a bean and routed to by {@link
 * io.opaa.indexing.pipeline.DocumentPipelineRegistry} on detected content, never on the extension
 * alone. Every chunk carries its pipeline's {@code id}/{@code version} ({@link
 * io.opaa.indexing.pipeline.ChunkPipelineMetadata}), raised only when a cut changes.
 *
 * <p>This package holds no job/document orchestration and never calls back into it; it only uses
 * core services from {@code io.opaa.indexing}, which consumes this package's public contract.
 */
package io.opaa.indexing.pipeline;
