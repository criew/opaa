package io.opaa.connection;

import io.opaa.auth.CurrentUser;
import io.opaa.common.FieldValidationException;
import io.opaa.connection.token.ConnectionLifecycleSetting;
import io.opaa.settings.SettingsSection;
import io.opaa.settings.SettingsValues;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The thresholds and periods of the connections: the value the administration set, {@code null}
 * while the factory default applies. Applied through {@link
 * ConnectionLifecycleAdministration#change}, which writes and audits nothing for an unchanged
 * value.
 */
@Component
class ConnectionLifecycleSettingsSection implements SettingsSection {

  static final String OUT_OF_RANGE = "OUT_OF_RANGE";

  private static final Map<String, ConnectionLifecycleSetting> KEYS = keys();

  private final ConnectionLifecycleAdministration administration;

  ConnectionLifecycleSettingsSection(ConnectionLifecycleAdministration administration) {
    this.administration = administration;
  }

  @Override
  public String key() {
    return "connectionLifecycle";
  }

  @Override
  public String label() {
    return "Schwellen und Fristen der Verbindungen";
  }

  @Override
  public Set<String> tables() {
    return Set.of("connection_lifecycle_settings");
  }

  @Override
  public Map<String, String> keyLabels() {
    return Map.of(
        "inactivityThresholdDays", "Inaktivitätsschwelle in Tagen",
        "privateLibraryDeletionDays", "Löschfrist privater Bibliotheken in Tagen",
        "expiredConnectionWarningThreshold", "Warnschwelle abgelaufener Konten je Zugang");
  }

  /**
   * A longer inactivity threshold keeps the secrets of persons who no longer sign in usable longer;
   * the deletion period decides how long copies of a deactivated person's sources stay, and a
   * shorter one erases them earlier. The warning threshold only changes a hint of the overview.
   */
  @Override
  public Set<String> securityRelevantKeys() {
    return Set.of("inactivityThresholdDays", "privateLibraryDeletionDays");
  }

  @Override
  public Map<String, Object> export(CurrentUser actor) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (ConnectionLifecycleAdministration.Entry entry : administration.read(actor).entries()) {
      values.put(keyOf(entry.setting()), entry.overridden() ? entry.value() : null);
    }
    return values;
  }

  @Override
  public void apply(CurrentUser actor, SettingsValues values) {
    for (Map.Entry<String, ConnectionLifecycleSetting> key : KEYS.entrySet()) {
      Long value = values.optionalLong(key.getKey());
      ConnectionLifecycleSetting setting = key.getValue();
      if (value != null && (value < setting.min() || value > setting.max())) {
        throw FieldValidationException.of(
            key.getKey(), OUT_OF_RANGE, ConnectionLifecycleAdministration.outOfRange(setting));
      }
      administration.change(actor, setting.name(), value == null ? null : value.intValue());
    }
  }

  private static String keyOf(ConnectionLifecycleSetting setting) {
    return KEYS.entrySet().stream()
        .filter(entry -> entry.getValue() == setting)
        .findFirst()
        .orElseThrow()
        .getKey();
  }

  private static Map<String, ConnectionLifecycleSetting> keys() {
    Map<String, ConnectionLifecycleSetting> keys = new LinkedHashMap<>();
    keys.put("inactivityThresholdDays", ConnectionLifecycleSetting.INACTIVITY_THRESHOLD_DAYS);
    keys.put(
        "privateLibraryDeletionDays", ConnectionLifecycleSetting.PRIVATE_LIBRARY_DELETION_DAYS);
    keys.put(
        "expiredConnectionWarningThreshold",
        ConnectionLifecycleSetting.EXPIRED_CONNECTION_WARNING_THRESHOLD);
    return keys;
  }
}
