package io.opaa.succession;

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

  SuccessionScheduler(
      SuccessionDetectionService detectionService, SuccessionRetentionService retentionService) {
    this.detectionService = detectionService;
    this.retentionService = retentionService;
  }

  @Scheduled(cron = "${opaa.succession.detection-cron:0 5 * * * *}")
  public void detectOpenSuccessions() {
    detectionService.runOnce();
  }

  /**
   * The retention deletion of the records, monthly like the audit log's own (ADR-0036, Entscheidung
   * 8) - half an hour after it, so the two passes never share a transaction window.
   */
  @Scheduled(cron = "0 30 3 1 * *")
  public void deleteExpiredSuccessionCases() {
    retentionService.runOnce();
  }
}
