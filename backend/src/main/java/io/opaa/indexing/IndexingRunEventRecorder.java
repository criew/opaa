package io.opaa.indexing;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records one run's protocol of skipped/rejected items and errors, one instance per run, held by
 * the executor for the duration of its {@code execute} call. Every executor runs on a single
 * thread, so the counter below needs no synchronization.
 *
 * <p>Capped at {@link #MAX_EVENTS_PER_RUN}: events beyond the cap are counted, not persisted, and
 * {@link #finalizeRun} writes that count to {@link IndexingJob#getEventsTruncatedCount()}. Both
 * methods swallow persistence failures - a propagated one would keep {@code completeJob}/{@code
 * failJob} from running and leave the job stuck {@link JobStatus#RUNNING}, blocking every future
 * run of that library.
 */
public final class IndexingRunEventRecorder implements IndexingEventSink {

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
  @Override
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
