/**
 * One indexing run: the {@link io.opaa.indexing.job.IndexingJob} row and its lifecycle ({@link
 * io.opaa.indexing.job.IndexingJobService}), the per-run protocol written through {@link
 * io.opaa.indexing.job.IndexingEventSink} and read back as {@link
 * io.opaa.indexing.job.IndexingRunDetail}, the status a library shows, what ends a run early
 * ({@link io.opaa.indexing.job.RunEndingFailures}), the recovery of orphaned runs and the codec of
 * a library's schedule.
 *
 * <p>The lowest package of the pipeline next to {@code chunk}: it knows nothing of attachments,
 * parsing, chunking, the chunk stores or connectors. The run's counts, its frame and its triggers
 * live in {@code source}, which uses this package, never the other way round.
 */
package io.opaa.indexing.job;
