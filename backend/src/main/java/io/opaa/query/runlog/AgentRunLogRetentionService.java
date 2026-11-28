package io.opaa.query.runlog;

import io.opaa.api.types.AuditEventType;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.settings.RetentionSetting;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and changes the retention periods of the step log and the sandbox log, 1..30 days each and
 * the sandbox log never longer than the step log. There is deliberately no way to switch the
 * deletion off: {@link AgentRunLogMaintenance} runs unconditionally.
 */
@Service
public class AgentRunLogRetentionService {

  private final AgentRunLogRetentionSettingsRepository repository;
  private final RetentionSetting setting;

  AgentRunLogRetentionService(
      AgentRunLogRetentionSettingsRepository repository, AuditEventRecorder auditEventRecorder) {
    this.repository = repository;
    this.setting =
        new RetentionSetting(
            auditEventRecorder,
            "agent_run_log_retention_settings",
            "Nur die Administration darf die Aufbewahrungsdauer einsehen und ändern",
            AuditEventType.AGENT_RUN_LOG_RETENTION_CHANGED,
            null,
            new RetentionSetting.Bounds(
                AgentRunLogRetentionSettings.MIN_RETENTION_DAYS,
                AgentRunLogRetentionSettings.MAX_RETENTION_DAYS,
                "Tagen",
                ""));
  }

  @Transactional(readOnly = true)
  public AgentRunLogRetentionSettings read(CurrentUser actor) {
    setting.requireAdmin(actor);
    return settingsRow();
  }

  @Transactional
  public AgentRunLogRetentionSettings updateRetentionDays(
      CurrentUser actor, int stepLogDays, int sandboxLogDays) {
    setting.requireAdmin(actor);
    setting.requireWithin("Die Aufbewahrungsdauer des Schrittprotokolls", stepLogDays);
    setting.requireWithin("Die Aufbewahrungsdauer des Sandbox-Protokolls", sandboxLogDays);
    if (sandboxLogDays > stepLogDays) {
      throw new ValidationException(
          "Das Sandbox-Protokoll darf nicht länger aufbewahrt werden als das Schrittprotokoll");
    }
    AgentRunLogRetentionSettings previous = settingsRow();
    if (previous.getStepLogRetentionDays() == stepLogDays
        && previous.getSandboxLogRetentionDays() == sandboxLogDays) {
      return previous;
    }
    setting.change(
        actor,
        Map.of(
            "stepLogRetentionDays", previous.getStepLogRetentionDays(),
            "sandboxLogRetentionDays", previous.getSandboxLogRetentionDays()),
        Map.of("stepLogRetentionDays", stepLogDays, "sandboxLogRetentionDays", sandboxLogDays),
        () -> repository.updateRetentionDays(stepLogDays, sandboxLogDays));
    return settingsRow();
  }

  private AgentRunLogRetentionSettings settingsRow() {
    return repository
        .findSingleton()
        .orElseThrow(() -> new NotFoundException("Aufbewahrungseinstellung nicht gefunden"));
  }
}
