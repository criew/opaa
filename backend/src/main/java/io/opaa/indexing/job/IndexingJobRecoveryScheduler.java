package io.opaa.indexing.job;

import io.opaa.instance.ScheduledRunLocks;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reclaims {@code indexing_jobs} rows stuck at {@link JobStatus#RUNNING} with no task left to
 * complete them. Such a row is not merely stale bookkeeping: the partial unique index locks its
 * library out of every future trigger, with no way to resolve it from the UI.
 *
 * <p>A run is given up once the instance that owns it is dead by the instance register, at startup
 * and every {@value #DEAD_OWNER_SWEEP_MILLIS} ms after (ADR-0053, Entscheidung 8); another
 * instance's live run stays. {@link #recoverStaleRunningJobs()} additionally covers a run of a live
 * instance whose task was dropped or that hangs. Each sweep runs on one instance at a time under its
 * scheduler lock and is idempotent.
 */
@Component
public class IndexingJobRecoveryScheduler {

  /** How often the runs of dead instances are given up, well below {@code dead-after}. */
  static final long DEAD_OWNER_SWEEP_MILLIS = 60_000L;

  private static final Logger log = LoggerFactory.getLogger(IndexingJobRecoveryScheduler.class);

  private final IndexingJobService indexingJobService;
  private final RunRecoverySettings properties;

  public IndexingJobRecoveryScheduler(
      IndexingJobService indexingJobService, RunRecoverySettings properties) {
    this.indexingJobService = indexingJobService;
    this.properties = properties;
  }

  /**
   * Runs once, when the application is ready: also gives up the rows that name no owner, which were
   * written before owners were recorded.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void recoverOnStartup() {
    int recovered =
        indexingJobService.recoverJobsWithoutOwner()
            + indexingJobService.recoverJobsOfDeadInstances();
    if (recovered > 0) {
      log.warn(
          "Recovered {} indexing job(s) of instances that are no longer alive - each is now"
              + " FAILED, freeing its library for a new trigger",
          recovered);
    }
  }

  /** Gives up the runs of instances that died since, without waiting for a restart. */
  @Scheduled(fixedDelay = DEAD_OWNER_SWEEP_MILLIS, initialDelay = DEAD_OWNER_SWEEP_MILLIS)
  @SchedulerLock(
      name = "indexing-job-dead-owner-recovery",
      lockAtLeastFor = ScheduledRunLocks.AT_LEAST_FOR_MINUTE,
      lockAtMostFor = ScheduledRunLocks.AT_MOST_FOR_SHORT)
  public void recoverRunsOfDeadInstances() {
    int recovered = indexingJobService.recoverJobsOfDeadInstances();
    if (recovered > 0) {
      log.warn(
          "Recovered {} indexing job(s) of instances that died - each is now FAILED, freeing its"
              + " library for a new trigger",
          recovered);
    }
  }

  /**
   * Runs periodically while the application keeps running. Every 15 minutes: frequent enough that a
   * library is not locked out for long once a run actually exceeds {@link
   * RunRecoverySettings#staleJobTimeout()}, infrequent enough not to matter for load.
   */
  @Scheduled(fixedDelay = 15 * 60 * 1000L)
  @SchedulerLock(
      name = "indexing-job-stale-recovery",
      lockAtLeastFor = "PT14M",
      lockAtMostFor = "PT30M")
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
