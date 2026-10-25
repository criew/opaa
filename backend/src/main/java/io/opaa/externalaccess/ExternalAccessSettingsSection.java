package io.opaa.externalaccess;

import io.opaa.auth.CurrentUser;
import io.opaa.settings.SettingsSection;
import io.opaa.settings.SettingsValues;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** The channel settings of the external AI tools, the switch of the installation included. */
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
  public Map<String, Object> export(CurrentUser actor) {
    ExternalAccessSettings.Values current = service.current().values();
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("enabled", current.enabled());
    values.put("tokenMaxLifetimeDays", current.tokenMaxLifetimeDays());
    values.put("tokenRateLimitPerHour", current.tokenRateLimitPerHour());
    values.put("allowedCidrs", current.allowedCidrs());
    values.put("massRetrievalAlertThreshold", current.massRetrievalAlertThreshold());
    values.put("serverInstructions", current.serverInstructions());
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
            values.optionalString("serverInstructions")));
  }
}
