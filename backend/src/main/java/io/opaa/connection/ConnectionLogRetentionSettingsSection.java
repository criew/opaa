package io.opaa.connection;

import io.opaa.auth.CurrentUser;
import io.opaa.connection.log.ConnectionLogRetentionService;
import io.opaa.settings.SettingsSection;
import io.opaa.settings.SettingsValues;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** The retention period of the connection log; its run state is not a setting. */
@Component
class ConnectionLogRetentionSettingsSection implements SettingsSection {

  private final ConnectionLogRetentionService service;

  ConnectionLogRetentionSettingsSection(ConnectionLogRetentionService service) {
    this.service = service;
  }

  @Override
  public String key() {
    return "connectionLogRetention";
  }

  @Override
  public String label() {
    return "Aufbewahrungsfrist des Verbindungsprotokolls";
  }

  @Override
  public Set<String> tables() {
    return Set.of("connection_log_retention_settings");
  }

  @Override
  public Map<String, String> keyLabels() {
    return Map.of("retentionMonths", "Aufbewahrungsfrist in Monaten");
  }

  @Override
  public Map<String, Object> export(CurrentUser actor) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("retentionMonths", service.read(actor).getRetentionMonths());
    return values;
  }

  @Override
  public void apply(CurrentUser actor, SettingsValues values) {
    service.updateRetentionMonths(actor, values.requireInt("retentionMonths"));
  }
}
