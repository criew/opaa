package io.opaa.permission;

import io.opaa.compliance.InstanceDisclosure.StoredValue;
import io.opaa.compliance.InstanceStateSource;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The retention of the rights history as this instance holds it, for the disclosure. */
@Component
class PermissionHistoryInstanceState implements InstanceStateSource {

  private final PermissionHistoryRetentionSettingsRepository repository;

  PermissionHistoryInstanceState(PermissionHistoryRetentionSettingsRepository repository) {
    this.repository = repository;
  }

  @Override
  public Map<String, StoredValue> storedSettings() {
    return repository
        .findSingleton()
        .map(
            settings ->
                Map.of(
                    "permission_history_retention_settings.retention_months",
                    new StoredValue(
                        Integer.toString(settings.getRetentionMonths()), settings.getUpdatedAt())))
        .orElse(Map.of());
  }
}
