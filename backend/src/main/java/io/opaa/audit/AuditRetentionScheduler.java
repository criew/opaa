package io.opaa.audit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the audit retention deletion automatically, monthly and without operator action
 * ("Automatische Löschung nach Ablauf, monatsweise und ohne Zutun"). The schedule only decides
 * *when* {@link AuditRetentionDeletionService#runOnce()} is called; how much a single call is
 * allowed to remove is entirely governed by the database function's own forward-only cap, not by
 * this class.
 *
 * <p>Runs on the 1st of every month at 03:00 server time - comfortably off business hours, and
 * aligned with the monthly partition boundary the deletion itself operates on, so a partition is
 * never more than a few weeks past its exact expiry before this notices it.
 */
@Component
public class AuditRetentionScheduler {

  private final AuditRetentionDeletionService deletionService;

  public AuditRetentionScheduler(AuditRetentionDeletionService deletionService) {
    this.deletionService = deletionService;
  }

  @Scheduled(cron = "0 0 3 1 * *")
  public void deleteExpiredAuditLogPartitions() {
    deletionService.runOnce();
  }
}
