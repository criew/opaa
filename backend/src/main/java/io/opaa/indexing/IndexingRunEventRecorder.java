package io.opaa.indexing;

import java.util.UUID;

/**
 * Records a single run's protocol of skipped/rejected items and errors (#513), one instance per run
 * - mirrors {@link IndexingRunProgress}'s own per-run instantiation pattern, held locally by each
 * {@link SourceIndexingExecutor} for the duration of its {@code execute} call. Every executor runs
 * on exactly one thread (its own {@code @Async} invocation), so the in-memory counter below needs
 * no synchronization.
 *
 * <p><b>Capped at {@link #MAX_EVENTS_PER_RUN} (Umfangserweiterung, Issue #513 comment).</b> A run
 * that skips thousands of items (a large HTTP_DIRECTORY tree behind bot protection, for instance)
 * must not turn its own protocol into an unbounded table scan or an unusable page - events beyond
 * the cap are counted, not persisted; {@link #overflowCount()} is written once, at the end of the
 * run, to {@link IndexingJob#getEventsTruncatedCount()} via {@code
 * IndexingJobService#recordEventsTruncated}, so the UI can render "… und N weitere" without listing
 * them.
 */
final class IndexingRunEventRecorder {

  static final int MAX_EVENTS_PER_RUN = 500;

  private final IndexingRunEventRepository repository;
  private final UUID jobId;
  private int persistedCount;
  private int overflowCount;

  IndexingRunEventRecorder(IndexingRunEventRepository repository, UUID jobId) {
    this.repository = repository;
    this.jobId = jobId;
  }

  /**
   * Records one event. {@code message} must already be the final, German, user-facing text - see
   * {@link IndexingRunEvent}'s Javadoc on what {@code reference} may and may not contain (never a
   * raw challenge/redirect URL).
   */
  void record(IndexingEventCategory category, String message, String reference) {
    if (persistedCount < MAX_EVENTS_PER_RUN) {
      repository.save(new IndexingRunEvent(jobId, category, message, reference));
      persistedCount++;
    } else {
      overflowCount++;
    }
  }

  /** How many events this run recorded beyond the cap, without persisting them. */
  int overflowCount() {
    return overflowCount;
  }
}
