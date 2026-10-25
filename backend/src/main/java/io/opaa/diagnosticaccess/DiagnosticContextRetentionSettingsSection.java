package io.opaa.diagnosticaccess;

import io.opaa.auth.CurrentUser;
import io.opaa.settings.SettingsSection;
import io.opaa.settings.SettingsValues;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** The retention period of the diagnostic context log; its run state is not a setting. */
@Component
class DiagnosticContextRetentionSettingsSection implements SettingsSection {

  private final DiagnosticContextRetentionService service;

  DiagnosticContextRetentionSettingsSection(DiagnosticContextRetentionService service) {
    this.service = service;
  }

  @Override
  public String key() {
    return "diagnosticContextRetention";
  }

  @Override
  public String label() {
    return "Aufbewahrungsfrist des Diagnoseprotokolls";
  }

  @Override
  public Set<String> tables() {
    return Set.of("diagnostic_context_retention_settings");
  }

  @Override
  public Map<String, String> keyLabels() {
    return Map.of("retentionMonths", "Aufbewahrungsfrist in Monaten");
  }

  /** A shorter period deletes evidence earlier. */
  @Override
  public Set<String> securityRelevantKeys() {
    return Set.of("retentionMonths");
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
