package io.opaa.externalaccess;

import io.opaa.auth.CurrentUser;
import io.opaa.settings.SettingsSection;
import io.opaa.settings.SettingsValues;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The channel settings of the external AI tools, the switch of the installation included, and the
 * reminder lead of a library's release ({@code null} while the factory default applies).
 */
@Component
class ExternalAccessSettingsSection implements SettingsSection {

  private final ExternalAccessSettingsService service;

  ExternalAccessSettingsSection(ExternalAccessSettingsService service) {
    this.service = service;
  }

  @Override
  public String key() {
    return "externalAccess";
  }

  @Override
  public String label() {
    return "Externe KI-Werkzeuge";
  }

  @Override
  public Set<String> tables() {
    return Set.of("external_access_settings");
  }

  @Override
  public Map<String, String> keyLabels() {
    return Map.of(
        "enabled",
        "Kanal eingeschaltet",
        "tokenMaxLifetimeDays",
        "Ablauf-Obergrenze der Zugangstokens in Tagen",
        "tokenRateLimitPerHour",
        "Kontingent je Token und Stunde",
        "allowedCidrs",
        "Netzbereiche",
        "massRetrievalAlertThreshold",
        "Schwelle des Abflussalarms",
        "serverInstructions",
        "Einleitungstext",
        "releaseReminderLeadDays",
        "Erinnerung vor Ablauf einer Freigabe in Tagen");
  }

  @Override
  public Set<String> securityRelevantKeys() {
    return Set.of(
        "enabled",
        "tokenMaxLifetimeDays",
        "tokenRateLimitPerHour",
        "allowedCidrs",
        "massRetrievalAlertThreshold");
  }

  @Override
  public Map<String, Object> export(CurrentUser actor) {
    ExternalAccessSettings.Values current = service.current().values();
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("enabled", current.enabled());
    values.put("tokenMaxLifetimeDays", current.tokenMaxLifetimeDays());
    values.put("tokenRateLimitPerHour", current.tokenRateLimitPerHour());
    values.put("allowedCidrs", current.allowedCidrs());
    values.put("massRetrievalAlertThreshold", current.massRetrievalAlertThreshold());
    values.put("serverInstructions", current.serverInstructions());
    values.put("releaseReminderLeadDays", current.releaseReminderLeadDays());
    return values;
  }

  @Override
  public void apply(CurrentUser actor, SettingsValues values) {
    service.update(
        actor,
        new ExternalAccessSettingsService.Update(
            values.requireBoolean("enabled"),
            values.requireInt("tokenMaxLifetimeDays"),
            values.requireInt("tokenRateLimitPerHour"),
            values.requireStrings("allowedCidrs"),
            values.requireInt("massRetrievalAlertThreshold"),
            values.optionalString("serverInstructions"),
            optionalDays(values, "releaseReminderLeadDays")));
  }

  /** A whole number of days the service bounds itself; one beyond an int is out of any range. */
  private static Integer optionalDays(SettingsValues values, String key) {
    Long days = values.optionalLong(key);
    if (days == null) {
      return null;
    }
    return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, days));
  }
}
