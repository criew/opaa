/**
 * The source/connector contract (ADR-0017, ADR-0018, ADR-0038).
 *
 * <p>Every source type is a {@link io.opaa.indexing.source.SourceConnector} bean in its own
 * subpackage, registered by that package's {@code @Configuration} and resolved by type through
 * {@link io.opaa.indexing.source.SourceConnectorRegistry}: its {@link
 * io.opaa.indexing.source.SourceConnectorDescriptor} answers what the administration would
 * otherwise branch on, the connector validates its configuration - the connector settings it owns
 * travel through here as an opaque {@link io.opaa.indexing.source.ConnectorData} - and tests the
 * connection, and optional abilities - {@link io.opaa.indexing.source.SourceBrowser}, {@link
 * io.opaa.indexing.source.OriginalAccess}, {@link io.opaa.indexing.source.PushIntakeHandler} - are
 * further interfaces of the same bean. A run-based type additionally registers a {@link
 * io.opaa.indexing.source.SourceIndexingExecutor}, resolved at trigger time by {@link
 * io.opaa.indexing.source.IndexingSourceExecutorRegistry}. No package outside the connectors refers
 * to one, and the connectors do not refer to each other. The run itself stays in {@code
 * io.opaa.indexing.job} and the ingestion of a single document in {@code
 * io.opaa.indexing.document}.
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
