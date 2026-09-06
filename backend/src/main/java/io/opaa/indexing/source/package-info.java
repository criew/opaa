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
 * <p>Every executor runs its body inside {@link io.opaa.indexing.source.IndexingRunTemplate}: the
 * frame owns progress, protocol, result mapping, failure translation, reconciliation by absence and
 * the run's cost, and a body only enumerates its source, hands each item to processing through
 * {@link io.opaa.indexing.source.IndexingRun} and reports a {@link
 * io.opaa.indexing.source.ListingOutcome}.
 *
 * <p>Types here and in the subpackages are public only because the executors and their helpers span
 * sibling packages; none of it is a cross-module API surface.
 */
package io.opaa.indexing.source;
