package io.opaa.diagnosticaccess;

import io.opaa.api.types.AuditEventType;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.NotFoundException;
import io.opaa.settings.RetentionSetting;
import java.util.Map;
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

  private final DiagnosticContextRetentionSettingsRepository repository;
  private final RetentionSetting setting;

  public DiagnosticContextRetentionService(
      DiagnosticContextRetentionSettingsRepository repository,
      AuditEventRecorder auditEventRecorder) {
    this.repository = repository;
    this.setting =
        new RetentionSetting(
            auditEventRecorder,
            "diagnostic_context_retention_settings",
            "Nur die Administration darf die Aufbewahrungsdauer einsehen und ändern",
            AuditEventType.DIAGNOSTIC_CONTEXT_RETENTION_CHANGED,
            null,
            new RetentionSetting.Bounds(
                DiagnosticContextRetentionSettings.MIN_RETENTION_MONTHS,
                DiagnosticContextRetentionSettings.MAX_RETENTION_MONTHS,
                "Monaten",
                ""));
  }

  @Transactional(readOnly = true)
  public DiagnosticContextRetentionSettings read(CurrentUser actor) {
    setting.requireAdmin(actor);
    return settingsRow();
  }

  @Transactional
  public DiagnosticContextRetentionSettings updateRetentionMonths(CurrentUser actor, int months) {
    setting.requireAdmin(actor);
    setting.requireWithin("Die Aufbewahrungsdauer", months);
    int previous = settingsRow().getRetentionMonths();
    setting.change(
        actor,
        Map.of("retentionMonths", previous),
        Map.of("retentionMonths", months),
        () -> repository.updateRetentionMonths(months));
    return settingsRow();
  }

  private DiagnosticContextRetentionSettings settingsRow() {
    return repository
        .findSingleton()
        .orElseThrow(() -> new NotFoundException("Aufbewahrungseinstellung nicht gefunden"));
  }
}
