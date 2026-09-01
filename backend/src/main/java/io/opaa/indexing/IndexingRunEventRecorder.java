package io.opaa.indexing;

import io.opaa.indexing.source.SourceIndexingExecutor;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records a single run's protocol of skipped/rejected items and errors, one instance per run -
 * mirrors {@link IndexingRunProgress}'s own per-run instantiation pattern, held locally by each
 * {@link SourceIndexingExecutor} for the duration of its {@code execute} call. Every executor runs
 * on exactly one thread (its own {@code @Async} invocation), so the in-memory counter below needs
 * no synchronization.
 *
 * <p>Public - constructed from every {@code source.*} executor package (#1113); still not part of
 * any cross-module API surface.
 *
 * <p>Capped at {@link #MAX_EVENTS_PER_RUN}: a run that skips thousands of items must not turn its
 * own protocol into an unbounded table scan or an unusable page - events beyond the cap are
 * counted, not persisted; {@link #finalizeRun} writes that count once, at the end of the run, to
 * {@link IndexingJob#getEventsTruncatedCount()}, so the UI can render "… und N weitere" without
 * listing them.
 *
 * <p>Never breaks the run it protocols: both {@link #record} and {@link #finalizeRun} swallow any
 * exception the persistence layer throws, logging it instead of propagating it - a run's own
 * outcome (processed/skipped/failed counters, {@link JobStatus}) must never depend on whether its
 * protocol could be written. A propagated failure would prevent {@link
 * IndexingJobService#completeJob}/{@code failJob} from ever running, leaving the job stuck {@link
 * JobStatus#RUNNING} forever and permanently blocking every future run of that library. Centralized
 * here rather than in each executor's own call sites, so every executor is covered by construction.
 */
public final class IndexingRunEventRecorder {

  private static final Logger log = LoggerFactory.getLogger(IndexingRunEventRecorder.class);

  static final int MAX_EVENTS_PER_RUN = 500;

  private final IndexingRunEventRepository repository;
  private final IndexingJobService indexingJobService;
  private final UUID jobId;
  private int persistedCount;
  private int overflowCount;

  public IndexingRunEventRecorder(
      IndexingRunEventRepository repository, IndexingJobService indexingJobService, UUID jobId) {
    this.repository = repository;
    this.indexingJobService = indexingJobService;
    this.jobId = jobId;
  }

  /**
   * Records one event. {@code message} must already be the final, German, user-facing text - see
   * {@link IndexingRunEvent}'s Javadoc on what {@code reference} may and may not contain (never a
   * raw challenge/redirect URL). Never throws - see the class Javadoc.
   */
  public void record(IndexingEventCategory category, String message, String reference) {
    if (persistedCount >= MAX_EVENTS_PER_RUN) {
      overflowCount++;
      return;
    }
    try {
      repository.save(new IndexingRunEvent(jobId, category, message, reference));
      persistedCount++;
    } catch (Exception e) {
      // Not persisted - counted as overflow rather than silently dropped, so
      // eventsTruncatedCount still reflects "the protocol is incomplete", even though the true
      // cause here is a write failure rather than the cap.
      log.warn("Failed to record indexing run event for job {}, continuing the run", jobId, e);
      overflowCount++;
    }
  }

  /**
   * Persists this run's overflow count on the job, once, at the end of a run - a no-op when nothing
   * was truncated. Never throws - see the class Javadoc; called from every executor's terminal
   * branches ({@code progress.complete()}/{@code progress.fail()}).
   */
  public void finalizeRun() {
    if (overflowCount == 0) {
      return;
    }
    try {
      indexingJobService.recordEventsTruncated(jobId, overflowCount);
    } catch (Exception e) {
      log.warn("Failed to record eventsTruncatedCount for job {}, continuing the run", jobId, e);
    }
  }

  /**
   * How many events this run recorded beyond the cap (or failed to persist), without listing them.
   */
  int overflowCount() {
    return overflowCount;
  }
}
