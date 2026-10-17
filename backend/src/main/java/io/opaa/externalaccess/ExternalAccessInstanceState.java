package io.opaa.externalaccess;

import io.opaa.compliance.InstanceDisclosure.StoredValue;
import io.opaa.compliance.InstanceStateSource;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The longest lifetime of an access token as this instance holds it, for the disclosure. The time
 * of change is the one of the whole settings row.
 */
@Component
class ExternalAccessInstanceState implements InstanceStateSource {

  private final ExternalAccessSettingsRepository repository;

  ExternalAccessInstanceState(ExternalAccessSettingsRepository repository) {
    this.repository = repository;
  }

  @Override
  public Map<String, StoredValue> storedSettings() {
    return repository
        .findSingleton()
        .map(
            settings ->
                Map.of(
                    "external_access_settings.token_max_lifetime_days",
                    new StoredValue(
                        Integer.toString(settings.getTokenMaxLifetimeDays()),
                        settings.getUpdatedAt())))
        .orElse(Map.of());
  }
}
