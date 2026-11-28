package io.opaa.connection.log;

import io.opaa.api.types.AuditEventType;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.settings.RetentionSetting;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and changes the connection log's retention period, system administration only, like the
 * periods of the rights history and the diagnostic context protocol. Every change is the governance
 * event {@code CONNECTION_LOG_RETENTION_CHANGED} with both values; switching the deletion off is
 * not possible.
 */
@Service
public class ConnectionLogRetentionService {

  private final ConnectionLogRetentionSettingsRepository repository;
  private final RetentionSetting setting;

  ConnectionLogRetentionService(
      ConnectionLogRetentionSettingsRepository repository, AuditEventRecorder auditEventRecorder) {
    this.repository = repository;
    this.setting =
        new RetentionSetting(
            auditEventRecorder,
            "connection_log_retention_settings",
            "Nur die Administration darf die Aufbewahrungsfrist des Verbindungsprotokolls"
                + " einsehen und ändern",
            AuditEventType.CONNECTION_LOG_RETENTION_CHANGED,
            "Aufbewahrungsfrist des Verbindungsprotokolls",
            new RetentionSetting.Bounds(
                ConnectionLogRetentionSettings.MIN_RETENTION_MONTHS,
                ConnectionLogRetentionSettings.MAX_RETENTION_MONTHS,
                "Monaten",
                ""));
  }

  @Transactional(readOnly = true)
  public ConnectionLogRetentionSettings read(CurrentUser actor) {
    setting.requireAdmin(actor);
    return settingsRow();
  }

  @Transactional
  public ConnectionLogRetentionSettings updateRetentionMonths(CurrentUser actor, int months) {
    setting.requireAdmin(actor);
    setting.requireWithin("Die Aufbewahrungsfrist des Verbindungsprotokolls", months);
    int previous = settingsRow().getRetentionMonths();
    setting.change(
        actor,
        Map.of("retentionMonths", previous),
        Map.of("retentionMonths", months),
        () -> repository.updateRetentionMonths(months));
    return settingsRow();
  }

  private ConnectionLogRetentionSettings settingsRow() {
    return repository
        .findSingleton()
        .orElseThrow(
            () -> new IllegalStateException("connection_log_retention_settings has no row id=1"));
  }
}
