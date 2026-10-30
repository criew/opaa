package io.opaa.account;

import io.opaa.compliance.InstanceDisclosure.StoredValue;
import io.opaa.compliance.InstanceStateSource;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The periods and the lockout of local accounts as this instance holds them, for the disclosure:
 * the lockout as in force within the limits of the operation. The time of change is the one of the
 * whole settings row.
 */
@Component
class LocalAuthInstanceState implements InstanceStateSource {

  private final LocalAuthSettingsRepository repository;
  private final LocalLockoutProperties lockoutLimits;

  LocalAuthInstanceState(
      LocalAuthSettingsRepository repository, LocalLockoutProperties lockoutLimits) {
    this.repository = repository;
    this.lockoutLimits = lockoutLimits;
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
                    value(settings.getInactiveDays(), settings),
                    "local_auth_settings.lockout_max_attempts",
                    value(
                        settings.values().lockoutMaxAttemptsInForce(lockoutLimits.maxAttempts()),
                        settings),
                    "local_auth_settings.lockout_duration_minutes",
                    value(
                        settings
                            .values()
                            .lockoutDurationMinutesInForce(lockoutLimits.minDurationMinutes()),
                        settings)))
        .orElse(Map.of());
  }

  private static StoredValue value(int value, LocalAuthSettings settings) {
    return new StoredValue(Integer.toString(value), settings.getUpdatedAt());
  }
}
