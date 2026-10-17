package io.opaa.audit;

import io.opaa.compliance.InstanceDisclosure.StoredValue;
import io.opaa.compliance.InstanceStateSource;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The retention of the audit log as this instance holds it, for the disclosure. */
@Component
class AuditInstanceState implements InstanceStateSource {

  private final AuditRetentionSettingsRepository repository;

  AuditInstanceState(AuditRetentionSettingsRepository repository) {
    this.repository = repository;
  }

  @Override
  public Map<String, StoredValue> storedSettings() {
    return repository
        .findSingleton()
        .map(
            settings ->
                Map.of(
                    "audit_retention_settings.retention_months",
                    new StoredValue(
                        Integer.toString(settings.getRetentionMonths()), settings.getUpdatedAt())))
        .orElse(Map.of());
  }
}
