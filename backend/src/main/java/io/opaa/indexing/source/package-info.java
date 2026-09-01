/**
 * The source/connector executor contract (ADR-0017, ADR-0018).
 *
 * <p>A {@link io.opaa.indexing.source.SourceIndexingExecutor} owns one {@link
 * io.opaa.indexing.source.IndexingSourceType} and is registered as a Spring bean, resolved at
 * trigger time by {@link io.opaa.indexing.source.IndexingSourceExecutorRegistry} - a new source
 * type is added by implementing the interface and wiring one more bean, never by editing the
 * registry or an existing executor. Each concrete source type lives in its own subpackage ({@code
 * source.rss}, {@code source.web}, {@code source.filesystem}, {@code source.attachment}).
 * Orchestration and services shared across every source type ({@code IndexingJob*}, {@code
 * IndexingRun*}, the scheduler, {@code FileProcessingService}, {@code DocumentService}, {@code
 * ChunkingService}, {@code SupportedDocumentFormats}, {@code VectorChunkStore}) stay in the core
 * {@link io.opaa.indexing} package.
 */
package io.opaa.indexing.source;
