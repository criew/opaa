/**
 * One indexing run: the {@link io.opaa.indexing.job.IndexingJob} row and its lifecycle ({@link
 * io.opaa.indexing.job.IndexingJobService}), the per-run protocol written through {@link
 * io.opaa.indexing.job.IndexingEventSink} and read back as {@link
 * io.opaa.indexing.job.IndexingRunDetail}, the running counts in {@link
 * io.opaa.indexing.job.IndexingRunProgress}, the status a library shows, and the schedules that
 * trigger and recover runs. {@link io.opaa.indexing.job.DocumentIndexingService} is where a trigger
 * turns into a run and hands over to the executor {@code source} resolves for the library's type.
 *
 * <p>Owns the sink contract {@code document} reports each document's outcome to and folds the
 * returned result into the run's counts; beyond that it knows nothing of parsing, chunking or the
 * chunk stores. {@code maintenance} writes into the same protocol; nothing here calls into it.
 */
package io.opaa.indexing.job;
