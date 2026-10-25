package io.opaa.audit;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.common.ValidationException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The period the grants of the anlassbezogene Klärung are kept: the factory default in the code,
 * overridden by the row the system administration sets (ADR-0060, Entscheidung 1). Read at every
 * deletion run without a cache, so a change acts on whichever instance runs next (ADR-0053). Every
 * change is an audit event with before and after: the period is a matter for the staff council. The
 * callers check the role of the system administration; this module does not know the caller.
 */
@Service
public class AuditIncidentScopeRetentionSettings {

  private static final UUID SETTING_OBJECT_ID =
      UUID.nameUUIDFromBytes(
          "audit_incident_scope_retention_settings".getBytes(StandardCharsets.UTF_8));

  private final AuditIncidentScopeRetentionSettingRepository repository;
  private final AuditEventRecorder auditEventRecorder;

  AuditIncidentScopeRetentionSettings(
      AuditIncidentScopeRetentionSettingRepository repository,
      AuditEventRecorder auditEventRecorder) {
    this.repository = repository;
    this.auditEventRecorder = auditEventRecorder;
  }

  /** The period in force, the factory default and whether the administration set its own. */
  public record Setting(AuditIncidentScopeRetention retention, boolean overridden) {
    public AuditIncidentScopeRetention defaults() {
      return AuditIncidentScopeRetention.defaults();
    }
  }

  /** The period in force now. */
  @Transactional(readOnly = true)
  public AuditIncidentScopeRetention current() {
    return setting().retention();
  }

  /**
   * Sets the period in calendar years; {@code null} returns to the factory default. A call that
   * changes nothing writes neither the setting nor an audit event.
   *
   * @throws ValidationException for a period outside 1 to 10 years
   */
  @Transactional
  public Setting change(UUID organizationId, UUID actorId, Integer retentionYears) {
    if (retentionYears != null
        && (retentionYears < AuditIncidentScopeRetention.MIN_YEARS
            || retentionYears > AuditIncidentScopeRetention.MAX_YEARS)) {
      throw new ValidationException(
          "Die Löschfrist der anlassbezogenen Klärungen muss zwischen "
              + AuditIncidentScopeRetention.MIN_YEARS
              + " und "
              + AuditIncidentScopeRetention.MAX_YEARS
              + " Jahren liegen");
    }
    Setting before = setting();
    boolean unchanged =
        retentionYears == null
            ? !before.overridden()
            : before.overridden()
                && before.retention().incidentScopeRetentionYears() == retentionYears;
    if (unchanged) {
      return before;
    }
    if (retentionYears == null) {
      repository.clear();
    } else {
      repository.upsert(retentionYears);
    }
    Setting after = setting();
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(actorId)
            .type(AuditEventType.INCIDENT_SCOPE_RETENTION_CHANGED)
            .object(
                AuditObjectType.SYSTEM_SETTING,
                SETTING_OBJECT_ID,
                "Löschfrist der anlassbezogenen Klärungen")
            .before(payload(before))
            .after(payload(after))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return after;
  }

  @Transactional(readOnly = true)
  public Setting setting() {
    return repository
        .findSingleton()
        .map(row -> new Setting(row.retention(), true))
        .orElseGet(() -> new Setting(AuditIncidentScopeRetention.defaults(), false));
  }

  private static Map<String, Object> payload(Setting setting) {
    return Map.of(
        "retentionYears", setting.retention().incidentScopeRetentionYears(),
        "overridden", setting.overridden());
  }
}
