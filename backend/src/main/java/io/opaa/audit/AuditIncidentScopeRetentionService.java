package io.opaa.audit;

import io.opaa.instance.RetiredRetentionVariables.Pause;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The deletion of the grants of the anlassbezogene Klärung after the {@link
 * AuditIncidentScopeRetention} in force at the run, approved ones and requests never approved
 * alike. The number of requests, approvals and ends of usability per month stays behind in {@code
 * audit_incident_scope_monthly_counts}; nothing else of a grant does. {@link
 * AuditIncidentScopeRetentionScheduler} is the only production caller.
 */
@Service
public class AuditIncidentScopeRetentionService {

  private static final Logger log =
      LoggerFactory.getLogger(AuditIncidentScopeRetentionService.class);

  private final AuditIncidentScopeGrantRepository repository;
  private final AuditIncidentScopeRetentionSettings settings;
  private final Clock clock;

  AuditIncidentScopeRetentionService(
      AuditIncidentScopeGrantRepository repository,
      AuditIncidentScopeRetentionSettings settings,
      Clock clock) {
    this.repository = repository;
    this.settings = settings;
    this.clock = clock;
  }

  /**
   * One deletion pass; idempotent, and a no-op while nothing is due. Year boundaries are those of
   * the clock's zone, the server time the schedule runs in.
   */
  @Transactional
  public int runOnce() {
    AuditIncidentScopeRetentionSettings.Setting setting = settings.setting();
    AuditIncidentScopeRetention retention = setting.retention();
    List<Pause> pauses = settings.pauses(setting);
    if (!pauses.isEmpty()) {
      pauses.forEach(pause -> log.error(pause.error("Incident scope retention")));
      return 0;
    }
    Instant cutoff = retention.deletionCutoff(clock.instant(), clock.getZone());
    int deleted =
        repository.deleteUsabilityEndedBefore(
            cutoff, cutoff.minus(AuditIncidentScopeGrant.USABLE_WINDOW));
    if (deleted > 0) {
      log.info(
          "Incident scope retention: {} grant(s) deleted, usable until before {}", deleted, cutoff);
    }
    return deleted;
  }
}
