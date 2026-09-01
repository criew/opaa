package io.opaa.diagnosticaccess;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and changes the retention period of the diagnostic context protocol (Leitplanke (i)). The
 * period is configurable within 1..24 months; there is deliberately no method to switch the
 * deletion off, and none can be added without also removing {@link
 * DiagnosticContextRetentionScheduler}, whose {@code @Scheduled} run is unconditional.
 */
@Service
public class DiagnosticContextRetentionService {

  /**
   * Fixed, well-known object id of the settings singleton in the audit trail - it is a system
   * setting, not an entity with an id of its own.
   */
  private static final UUID SETTINGS_OBJECT_ID =
      UUID.nameUUIDFromBytes("diagnostic_context_retention_settings".getBytes());

  private final DiagnosticContextRetentionSettingsRepository repository;
  private final AuditEventRecorder auditEventRecorder;

  public DiagnosticContextRetentionService(
      DiagnosticContextRetentionSettingsRepository repository,
      AuditEventRecorder auditEventRecorder) {
    this.repository = repository;
    this.auditEventRecorder = auditEventRecorder;
  }

  @Transactional(readOnly = true)
  public DiagnosticContextRetentionSettings read(CurrentUser actor) {
    requireSystemAdmin(actor);
    return repository
        .findSingleton()
        .orElseThrow(() -> new NotFoundException("Aufbewahrungseinstellung nicht gefunden"));
  }

  @Transactional
  public DiagnosticContextRetentionSettings updateRetentionMonths(CurrentUser actor, int months) {
    requireSystemAdmin(actor);
    if (months < DiagnosticContextRetentionSettings.MIN_RETENTION_MONTHS
        || months > DiagnosticContextRetentionSettings.MAX_RETENTION_MONTHS) {
      throw new ValidationException(
          "Die Aufbewahrungsdauer muss zwischen "
              + DiagnosticContextRetentionSettings.MIN_RETENTION_MONTHS
              + " und "
              + DiagnosticContextRetentionSettings.MAX_RETENTION_MONTHS
              + " Monaten liegen");
    }
    int previous = read(actor).getRetentionMonths();
    repository.updateRetentionMonths(months);
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(actor.organizationId())
            .actor(actor.id())
            .type(AuditEventType.DIAGNOSTIC_CONTEXT_RETENTION_CHANGED)
            .object(AuditObjectType.SYSTEM_SETTING, SETTINGS_OBJECT_ID, null)
            .before(Map.of("retentionMonths", previous))
            .after(Map.of("retentionMonths", months))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return repository
        .findSingleton()
        .orElseThrow(() -> new NotFoundException("Aufbewahrungseinstellung nicht gefunden"));
  }

  private void requireSystemAdmin(CurrentUser actor) {
    if (!actor.isSystemAdmin()) {
      throw new AccessDeniedException(
          "Nur die Administration darf die Aufbewahrungsdauer einsehen und ändern");
    }
  }
}
