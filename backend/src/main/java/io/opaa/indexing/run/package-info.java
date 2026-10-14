/**
 * The run frame and its triggers. A run starts by hand or on a library's schedule through {@link
 * io.opaa.indexing.run.DocumentIndexingService} and {@link
 * io.opaa.indexing.run.LibraryIndexingScheduler}, on a push notification through {@link
 * io.opaa.indexing.run.SourceEventIntake}. Every executor runs its body inside {@link
 * io.opaa.indexing.run.IndexingRunTemplate}: the frame owns progress, protocol, result mapping,
 * failure translation, reconciliation by absence and the run's cost, and a body only enumerates its
 * source and hands each item to processing through {@link io.opaa.indexing.run.IndexingRun}.
 * Connectors call this package; it resolves target and secret through {@code indexing.credentials}
 * and reaches a connector through {@code indexing.registry}, neither of which a connector uses.
 */
package io.opaa.indexing.run;
