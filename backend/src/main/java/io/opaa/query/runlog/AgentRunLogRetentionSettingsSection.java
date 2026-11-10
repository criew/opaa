package io.opaa.query.runlog;

import io.opaa.auth.CurrentUser;
import io.opaa.settings.SettingsSection;
import io.opaa.settings.SettingsValues;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** The retention periods of the step log and the sandbox log; their run state is not a setting. */
@Component
class AgentRunLogRetentionSettingsSection implements SettingsSection {

  private final AgentRunLogRetentionService service;

  AgentRunLogRetentionSettingsSection(AgentRunLogRetentionService service) {
    this.service = service;
  }

  @Override
  public String key() {
    return "agentRunLogRetention";
  }

  @Override
  public String label() {
    return "Aufbewahrungsfrist des Schrittprotokolls der Agenten";
  }

  @Override
  public Set<String> tables() {
    return Set.of("agent_run_log_retention_settings");
  }

  @Override
  public Map<String, String> keyLabels() {
    return Map.of(
        "stepLogRetentionDays", "Aufbewahrungsfrist des Schrittprotokolls in Tagen",
        "sandboxLogRetentionDays", "Aufbewahrungsfrist des Sandbox-Protokolls in Tagen");
  }

  @Override
  public Map<String, Object> export(CurrentUser actor) {
    AgentRunLogRetentionSettings settings = service.read(actor);
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("stepLogRetentionDays", settings.getStepLogRetentionDays());
    values.put("sandboxLogRetentionDays", settings.getSandboxLogRetentionDays());
    return values;
  }

  @Override
  public void apply(CurrentUser actor, SettingsValues values) {
    service.updateRetentionDays(
        actor,
        values.requireInt("stepLogRetentionDays"),
        values.requireInt("sandboxLogRetentionDays"));
  }
}
