package io.opaa.connection.log;

import io.opaa.compliance.InstanceDisclosure.StoredValue;
import io.opaa.compliance.InstanceStateSource;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The retention of the connection log as this instance holds it, for the disclosure. */
@Component
class ConnectionLogInstanceState implements InstanceStateSource {

  private final ConnectionLogRetentionSettingsRepository repository;

  ConnectionLogInstanceState(ConnectionLogRetentionSettingsRepository repository) {
    this.repository = repository;
  }

  @Override
  public Map<String, StoredValue> storedSettings() {
    return repository
        .findSingleton()
        .map(
            settings ->
                Map.of(
                    "connection_log_retention_settings.retention_months",
                    new StoredValue(
                        Integer.toString(settings.getRetentionMonths()), settings.getUpdatedAt())))
        .orElse(Map.of());
  }
}
