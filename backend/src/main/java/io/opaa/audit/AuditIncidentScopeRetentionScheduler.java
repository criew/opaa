package io.opaa.audit;

import io.opaa.instance.ScheduledRunLocks;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the deletion of the grants of the anlassbezogene Klärung daily at 03:15 server time, without
 * operator action and without a switch; only the period is configurable. Daily rather than once a
 * year, so a server that was down at the turn of the year catches up the next night. The
 * scheduled-run lock lets one instance run it per night; a second run would find nothing left.
 */
@Component
public class AuditIncidentScopeRetentionScheduler {

  private final AuditIncidentScopeRetentionService retentionService;

  AuditIncidentScopeRetentionScheduler(AuditIncidentScopeRetentionService retentionService) {
    this.retentionService = retentionService;
  }

  @Scheduled(cron = "0 15 3 * * *")
  @SchedulerLock(
      name = "audit-incident-scope-retention",
      lockAtLeastFor = ScheduledRunLocks.AT_LEAST_FOR_DAILY,
      lockAtMostFor = ScheduledRunLocks.AT_MOST_FOR_NIGHTLY)
  public void deleteExpiredIncidentScopeGrants() {
    retentionService.runOnce();
  }
}
