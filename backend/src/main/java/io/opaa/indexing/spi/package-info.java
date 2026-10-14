/**
 * The connector contract (ADR-0017, ADR-0018, ADR-0038). Every source type is a {@link
 * io.opaa.indexing.spi.SourceConnector} bean in its own subpackage of {@code indexing.source}: its
 * {@link io.opaa.indexing.spi.SourceConnectorDescriptor} answers what the administration would
 * otherwise branch on, the connector validates its configuration - the connector settings it owns
 * travel through here as an opaque {@link io.opaa.indexing.spi.ConnectorData} - and tests the
 * connection, and optional abilities - {@link io.opaa.indexing.spi.SourceBrowser}, {@link
 * io.opaa.indexing.spi.OriginalAccess}, {@link io.opaa.indexing.spi.PushIntakeHandler} - are
 * further interfaces of the same bean. A run-based type additionally registers a {@link
 * io.opaa.indexing.spi.SourceIndexingExecutor}, whose body reports a {@link
 * io.opaa.indexing.spi.ListingOutcome} and asks for its secret through {@link
 * io.opaa.indexing.spi.RunCredentials}. The removal of what a complete run did not meet goes
 * through {@link io.opaa.indexing.spi.VanishedDocumentReconciler}, which {@code maintenance}
 * implements. Builds on {@code job}, {@code attachment} and {@code document}.
 */
package io.opaa.indexing.spi;
