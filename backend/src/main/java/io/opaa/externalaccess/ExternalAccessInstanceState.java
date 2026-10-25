package io.opaa.externalaccess;

import io.opaa.compliance.InstanceDisclosure.StoredValue;
import io.opaa.compliance.InstanceStateSource;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The longest lifetime of an access token and the reminder lead of a release as this instance holds
 * them, for the disclosure. The time of change is the one of the whole settings row.
 */
@Component
class ExternalAccessInstanceState implements InstanceStateSource {

  private final ExternalAccessSettingsRepository repository;
  private final ExternalAccessSettingsService service;

  ExternalAccessInstanceState(
      ExternalAccessSettingsRepository repository, ExternalAccessSettingsService service) {
    this.repository = repository;
    this.service = service;
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
                        settings.getUpdatedAt()),
                    "external_access_settings.release_reminder_lead_days",
                    new StoredValue(
                        Integer.toString(
                            settings
                                .values()
                                .releaseReminderLeadDaysInForce(
                                    service.maxReleaseReminderLeadDays())),
                        settings.getUpdatedAt())))
        .orElse(Map.of());
  }
}
