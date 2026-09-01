/**
 * The source/connector executor contract (ADR-0017, ADR-0018).
 *
 * <p>A {@link io.opaa.indexing.source.SourceIndexingExecutor} owns one {@link
 * io.opaa.indexing.source.IndexingSourceType} and is registered as a Spring bean, resolved at
 * trigger time by {@link io.opaa.indexing.source.IndexingSourceExecutorRegistry} - a new source
 * type is added by implementing the interface and wiring one more bean, never by editing the
 * registry or an existing executor. Each concrete source type lives in its own subpackage; job
 * orchestration and the services shared across all of them stay in the core {@code
 * io.opaa.indexing} package.
 *
 * <p>Types here and in the subpackages are public only because the executors and their helpers span
 * sibling packages; none of it is a cross-module API surface.
 */
package io.opaa.indexing.source;
