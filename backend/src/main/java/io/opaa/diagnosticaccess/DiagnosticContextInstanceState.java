package io.opaa.diagnosticaccess;

import io.opaa.compliance.InstanceDisclosure.StoredValue;
import io.opaa.compliance.InstanceStateSource;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The retention of the diagnostic context log as this instance holds it, for the disclosure. */
@Component
class DiagnosticContextInstanceState implements InstanceStateSource {

  private final DiagnosticContextRetentionSettingsRepository repository;

  DiagnosticContextInstanceState(DiagnosticContextRetentionSettingsRepository repository) {
    this.repository = repository;
  }

  @Override
  public Map<String, StoredValue> storedSettings() {
    return repository
        .findSingleton()
        .map(
            settings ->
                Map.of(
                    "diagnostic_context_retention_settings.retention_months",
                    new StoredValue(
                        Integer.toString(settings.getRetentionMonths()), settings.getUpdatedAt())))
        .orElse(Map.of());
  }
}
