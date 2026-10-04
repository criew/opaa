package io.opaa.connection.log;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ValidationException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
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

  static final UUID SETTINGS_OBJECT_ID =
      UUID.nameUUIDFromBytes("connection_log_retention_settings".getBytes(StandardCharsets.UTF_8));

  private final ConnectionLogRetentionSettingsRepository repository;
  private final AuditEventRecorder auditEventRecorder;

  ConnectionLogRetentionService(
      ConnectionLogRetentionSettingsRepository repository, AuditEventRecorder auditEventRecorder) {
    this.repository = repository;
    this.auditEventRecorder = auditEventRecorder;
  }

  @Transactional(readOnly = true)
  public ConnectionLogRetentionSettings read(CurrentUser actor) {
    requireSystemAdmin(actor);
    return settingsRow();
  }

  @Transactional
  public ConnectionLogRetentionSettings updateRetentionMonths(CurrentUser actor, int months) {
    requireSystemAdmin(actor);
    if (months < ConnectionLogRetentionSettings.MIN_RETENTION_MONTHS
        || months > ConnectionLogRetentionSettings.MAX_RETENTION_MONTHS) {
      throw new ValidationException(
          "Die Aufbewahrungsfrist des Verbindungsprotokolls muss zwischen "
              + ConnectionLogRetentionSettings.MIN_RETENTION_MONTHS
              + " und "
              + ConnectionLogRetentionSettings.MAX_RETENTION_MONTHS
              + " Monaten liegen");
    }
    int previous = settingsRow().getRetentionMonths();
    if (repository.updateRetentionMonths(months) != 1) {
      throw new IllegalStateException("connection_log_retention_settings has no row with id=1");
    }
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(actor.organizationId())
            .actor(actor.id())
            .type(AuditEventType.CONNECTION_LOG_RETENTION_CHANGED)
            .object(
                AuditObjectType.SYSTEM_SETTING,
                SETTINGS_OBJECT_ID,
                "Aufbewahrungsfrist des Verbindungsprotokolls")
            .before(Map.of("retentionMonths", previous))
            .after(Map.of("retentionMonths", months))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return settingsRow();
  }

  private ConnectionLogRetentionSettings settingsRow() {
    return repository
        .findSingleton()
        .orElseThrow(
            () -> new IllegalStateException("connection_log_retention_settings has no row id=1"));
  }

  private static void requireSystemAdmin(CurrentUser actor) {
    if (!actor.isSystemAdmin()) {
      throw new AccessDeniedException(
          "Nur die Administration darf die Aufbewahrungsfrist des Verbindungsprotokolls einsehen"
              + " und ändern");
    }
  }
}
