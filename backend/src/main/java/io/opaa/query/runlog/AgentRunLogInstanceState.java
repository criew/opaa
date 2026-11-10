package io.opaa.query.runlog;

import io.opaa.compliance.InstanceDisclosure.StoredValue;
import io.opaa.compliance.InstanceStateSource;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The retention periods of the agent run logs as this instance holds them, for the disclosure. */
@Component
class AgentRunLogInstanceState implements InstanceStateSource {

  private final AgentRunLogRetentionSettingsRepository repository;

  AgentRunLogInstanceState(AgentRunLogRetentionSettingsRepository repository) {
    this.repository = repository;
  }

  @Override
  public Map<String, StoredValue> storedSettings() {
    return repository
        .findSingleton()
        .map(
            settings ->
                Map.of(
                    "agent_run_log_retention_settings.step_log_retention_days",
                    new StoredValue(
                        Integer.toString(settings.getStepLogRetentionDays()),
                        settings.getUpdatedAt()),
                    "agent_run_log_retention_settings.sandbox_log_retention_days",
                    new StoredValue(
                        Integer.toString(settings.getSandboxLogRetentionDays()),
                        settings.getUpdatedAt())))
        .orElse(Map.of());
  }
}
