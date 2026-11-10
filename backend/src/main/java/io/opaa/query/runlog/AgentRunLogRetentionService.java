package io.opaa.query.runlog;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and changes the retention periods of the step log and the sandbox log, 1..30 days each and
 * the sandbox log never longer than the step log. There is deliberately no way to switch the
 * deletion off: {@link AgentRunLogMaintenance} runs unconditionally.
 */
@Service
public class AgentRunLogRetentionService {

  private static final String ADMIN_ONLY =
      "Nur die Administration darf die Aufbewahrungsdauer einsehen und ändern";

  private static final UUID SETTINGS_OBJECT_ID =
      UUID.nameUUIDFromBytes("agent_run_log_retention_settings".getBytes(StandardCharsets.UTF_8));

  private final AgentRunLogRetentionSettingsRepository repository;
  private final AuditEventRecorder auditEventRecorder;

  AgentRunLogRetentionService(
      AgentRunLogRetentionSettingsRepository repository, AuditEventRecorder auditEventRecorder) {
    this.repository = repository;
    this.auditEventRecorder = auditEventRecorder;
  }

  @Transactional(readOnly = true)
  public AgentRunLogRetentionSettings read(CurrentUser actor) {
    actor.requireSystemAdmin(ADMIN_ONLY);
    return repository
        .findSingleton()
        .orElseThrow(() -> new NotFoundException("Aufbewahrungseinstellung nicht gefunden"));
  }

  @Transactional
  public AgentRunLogRetentionSettings updateRetentionDays(
      CurrentUser actor, int stepLogDays, int sandboxLogDays) {
    actor.requireSystemAdmin(ADMIN_ONLY);
    requireBounds(stepLogDays, "des Schrittprotokolls");
    requireBounds(sandboxLogDays, "des Sandbox-Protokolls");
    if (sandboxLogDays > stepLogDays) {
      throw new ValidationException(
          "Das Sandbox-Protokoll darf nicht länger aufbewahrt werden als das Schrittprotokoll");
    }
    AgentRunLogRetentionSettings previous = read(actor);
    if (previous.getStepLogRetentionDays() == stepLogDays
        && previous.getSandboxLogRetentionDays() == sandboxLogDays) {
      return previous;
    }
    repository.updateRetentionDays(stepLogDays, sandboxLogDays);
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(actor.organizationId())
            .actor(actor.id())
            .type(AuditEventType.AGENT_RUN_LOG_RETENTION_CHANGED)
            .object(AuditObjectType.SYSTEM_SETTING, SETTINGS_OBJECT_ID, null)
            .before(
                Map.of(
                    "stepLogRetentionDays", previous.getStepLogRetentionDays(),
                    "sandboxLogRetentionDays", previous.getSandboxLogRetentionDays()))
            .after(
                Map.of(
                    "stepLogRetentionDays", stepLogDays,
                    "sandboxLogRetentionDays", sandboxLogDays))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return repository
        .findSingleton()
        .orElseThrow(() -> new NotFoundException("Aufbewahrungseinstellung nicht gefunden"));
  }

  private static void requireBounds(int days, String which) {
    if (days < AgentRunLogRetentionSettings.MIN_RETENTION_DAYS
        || days > AgentRunLogRetentionSettings.MAX_RETENTION_DAYS) {
      throw new ValidationException(
          "Die Aufbewahrungsdauer "
              + which
              + " muss zwischen "
              + AgentRunLogRetentionSettings.MIN_RETENTION_DAYS
              + " und "
              + AgentRunLogRetentionSettings.MAX_RETENTION_DAYS
              + " Tagen liegen");
    }
  }
}
