package io.opaa.indexing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reclaims {@code indexing_jobs} rows stuck at {@link JobStatus#RUNNING} with no {@code @Async}
 * task left to ever complete them. A {@code RUNNING} row is not merely stale bookkeeping - the
 * partial unique index locks its one library out of every future trigger (409, {@code
 * IndexingJobService#isJobRunning}), with no way to resolve it from the UI. Two independent
 * recovery paths cover the two ways a row gets orphaned:
 *
 * <ul>
 *   <li>{@link #recoverOnStartup()} - a fresh JVM cannot possibly still be running the task any
 *       {@code RUNNING} row refers to, so every one of them is orphaned the moment the application
 *       comes back up, whether the previous process crashed mid-run or the task was silently
 *       discarded by a full queue.
 *   <li>{@link #recoverStaleRunningJobs()} - catches everything that does not involve a restart at
 *       all: a task silently dropped while the application kept running, or a run that is still
 *       technically in progress but has been for implausibly long.
 * </ul>
 */
@Component
public class IndexingJobRecoveryScheduler {

  private static final Logger log = LoggerFactory.getLogger(IndexingJobRecoveryScheduler.class);

  private final IndexingJobService indexingJobService;
  private final IndexingProperties properties;

  public IndexingJobRecoveryScheduler(
      IndexingJobService indexingJobService, IndexingProperties properties) {
    this.indexingJobService = indexingJobService;
    this.properties = properties;
  }

  /**
   * Runs once, right after the application is ready to serve traffic. Deliberately an {@link
   * ApplicationReadyEvent} listener, not something invoked from {@code main} directly: by the time
   * this fires, the datasource and every {@code @Async} executor are fully initialized, so there is
   * no risk of racing the very infrastructure this recovery depends on.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void recoverOnStartup() {
    int recovered = indexingJobService.recoverJobsOrphanedByRestart();
    if (recovered > 0) {
      log.warn(
          "Recovered {} indexing job(s) left RUNNING by a previous application run - each is now"
              + " FAILED, freeing its library for a new trigger",
          recovered);
    }
  }

  /**
   * Runs periodically while the application keeps running, independent of {@link
   * #recoverOnStartup}. Every 15 minutes: frequent enough that a library is not locked out for long
   * once a run actually exceeds {@link IndexingProperties#staleJobTimeout()}, infrequent enough not
   * to matter for load.
   */
  @Scheduled(fixedDelay = 15 * 60 * 1000L)
  public void recoverStaleRunningJobs() {
    int recovered = indexingJobService.recoverStaleJobs(properties.staleJobTimeout());
    if (recovered > 0) {
      log.warn(
          "Recovered {} indexing job(s) RUNNING for longer than {} - each is now FAILED, freeing"
              + " its library for a new trigger",
          recovered,
          properties.staleJobTimeout());
    }
  }
}
