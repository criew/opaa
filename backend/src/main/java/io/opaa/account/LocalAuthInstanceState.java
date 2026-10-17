package io.opaa.account;

import io.opaa.compliance.InstanceDisclosure.StoredValue;
import io.opaa.compliance.InstanceStateSource;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The periods of local accounts as this instance holds them, for the disclosure. The time of change
 * is the one of the whole settings row.
 */
@Component
class LocalAuthInstanceState implements InstanceStateSource {

  private final LocalAuthSettingsRepository repository;

  LocalAuthInstanceState(LocalAuthSettingsRepository repository) {
    this.repository = repository;
  }

  @Override
  public Map<String, StoredValue> storedSettings() {
    return repository
        .findSingleton()
        .map(
            settings ->
                Map.of(
                    "local_auth_settings.invitation_token_ttl_hours",
                    value(settings.getInvitationTokenTtlHours(), settings),
                    "local_auth_settings.reset_token_ttl_minutes",
                    value(settings.getResetTokenTtlMinutes(), settings),
                    "local_auth_settings.default_expiry_days",
                    value(settings.getDefaultExpiryDays(), settings),
                    "local_auth_settings.inactive_days",
                    value(settings.getInactiveDays(), settings)))
        .orElse(Map.of());
  }

  private static StoredValue value(int value, LocalAuthSettings settings) {
    return new StoredValue(Integer.toString(value), settings.getUpdatedAt());
  }
}
