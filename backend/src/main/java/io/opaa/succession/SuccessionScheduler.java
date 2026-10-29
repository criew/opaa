package io.opaa.succession;

import io.opaa.instance.ScheduledRunLocks;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the detection of ADR-0036, Entscheidung 6 - by default five minutes past every hour, the
 * "stündlich" the ADR names; the expression is configurable, the run itself is not switchable off.
 * Separate from the service so a test can drive one pass without Spring's scheduling machinery.
 */
@Component
public class SuccessionScheduler {

  private final SuccessionDetectionService detectionService;
  private final SuccessionRetentionService retentionService;

  /**
   * Takes {@code properties} only so they are bound, and validated, before the expression below is
   * read: a blank or malformed value then fails naming its variable.
   */
  SuccessionScheduler(
      SuccessionDetectionService detectionService,
      SuccessionRetentionService retentionService,
      SuccessionProperties properties) {
    this.detectionService = detectionService;
    this.retentionService = retentionService;
  }

  @Scheduled(
      cron =
          "${opaa.succession.detection-cron:" + SuccessionProperties.DEFAULT_DETECTION_CRON + "}")
  @SchedulerLock(
      name = "succession-detection",
      lockAtLeastFor = ScheduledRunLocks.AT_LEAST_FOR_MINUTE,
      lockAtMostFor = ScheduledRunLocks.AT_MOST_FOR_SHORT)
  public void detectOpenSuccessions() {
    detectionService.runOnce();
  }

  /**
   * The retention deletion of the records, monthly like the audit log's own (ADR-0036, Entscheidung
   * 8) - half an hour after it, so the two passes never share a transaction window.
   */
  @Scheduled(cron = "0 30 3 1 * *")
  @SchedulerLock(
      name = "succession-case-retention",
      lockAtLeastFor = ScheduledRunLocks.AT_LEAST_FOR_DAILY,
      lockAtMostFor = ScheduledRunLocks.AT_MOST_FOR_NIGHTLY)
  public void deleteExpiredSuccessionCases() {
    retentionService.runOnce();
  }
}
