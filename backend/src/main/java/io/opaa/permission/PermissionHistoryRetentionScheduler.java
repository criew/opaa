package io.opaa.permission;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the rights history's retention deletion monthly and without operator action - the automatic
 * deletion docs/features/security-and-compliance.md promises ("monatsweise und ohne Zutun").
 * Deliberately carries no enable flag: only the period is configurable, the deletion itself is not
 * switchable off.
 *
 * <p>On the 1st of every month at 04:00 server time, after the protocol's own deletion (03:00) and
 * the diagnostic context protocol's (03:30), so the three passes do not contend for the same
 * connections.
 */
@Component
public class PermissionHistoryRetentionScheduler {

  private final PermissionHistoryRetentionDeletionService deletionService;

  PermissionHistoryRetentionScheduler(PermissionHistoryRetentionDeletionService deletionService) {
    this.deletionService = deletionService;
  }

  @Scheduled(cron = "0 0 4 1 * *")
  public void deleteExpiredPermissionHistory() {
    deletionService.runOnce();
  }
}
