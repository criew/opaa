package io.opaa.audit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the deletion of the grants of the anlassbezogene Klärung daily at 03:15 server time, without
 * operator action and without a switch; only the period is configurable. Daily rather than once a
 * year, so a server that was down at the turn of the year catches up the next night. One process
 * per installation (ADR-0021); a second run finds nothing left.
 */
@Component
public class AuditIncidentScopeRetentionScheduler {

  private final AuditIncidentScopeRetentionService retentionService;

  AuditIncidentScopeRetentionScheduler(AuditIncidentScopeRetentionService retentionService) {
    this.retentionService = retentionService;
  }

  @Scheduled(cron = "0 15 3 * * *")
  public void deleteExpiredIncidentScopeGrants() {
    retentionService.runOnce();
  }
}
