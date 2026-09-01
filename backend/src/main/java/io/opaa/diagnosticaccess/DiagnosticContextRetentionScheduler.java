package io.opaa.diagnosticaccess;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the retention deletion of the diagnostic context protocol monthly, without operator action
 * and without a condition. Deliberately carries no {@code @ConditionalOnProperty} and reads no
 * enable flag: Leitplanke (i) makes the period configurable and the deletion itself not switchable,
 * so there is no configuration this class could consult to skip a run.
 */
@Component
public class DiagnosticContextRetentionScheduler {

  private final DiagnosticContextRetentionDeletionService deletionService;

  public DiagnosticContextRetentionScheduler(
      DiagnosticContextRetentionDeletionService deletionService) {
    this.deletionService = deletionService;
  }

  @Scheduled(cron = "0 30 3 1 * *")
  public void deleteExpiredDiagnosticContextPartitions() {
    deletionService.runOnce();
  }
}
