package io.opaa.permission;

import io.opaa.auth.CurrentUser;
import io.opaa.settings.SettingsSection;
import io.opaa.settings.SettingsValues;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** The maximum retention period of the rights history; its run state is not a setting. */
@Component
class PermissionHistoryRetentionSettingsSection implements SettingsSection {

  private final PermissionHistoryRetentionService service;

  PermissionHistoryRetentionSettingsSection(PermissionHistoryRetentionService service) {
    this.service = service;
  }

  @Override
  public String key() {
    return "permissionHistoryRetention";
  }

  @Override
  public String label() {
    return "Aufbewahrungshöchstdauer der Rechtehistorie";
  }

  @Override
  public Set<String> tables() {
    return Set.of("permission_history_retention_settings");
  }

  @Override
  public Map<String, String> keyLabels() {
    return Map.of("retentionMonths", "Aufbewahrungshöchstdauer in Monaten");
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
