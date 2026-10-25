package io.opaa.settings;

import io.opaa.audit.AuditIncidentScopeRetentionSettings;
import io.opaa.auth.CurrentUser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The period the grants of the anlassbezogene Klärung are kept; {@code null} while the factory
 * default applies. A shorter period deletes evidence earlier.
 */
@Component
class IncidentScopeRetentionSettingsSection implements SettingsSection {

  private final AuditIncidentScopeRetentionSettings settings;

  IncidentScopeRetentionSettingsSection(AuditIncidentScopeRetentionSettings settings) {
    this.settings = settings;
  }

  @Override
  public String key() {
    return "incidentScopeRetention";
  }

  @Override
  public String label() {
    return "Löschfrist der anlassbezogenen Klärungen";
  }

  @Override
  public Set<String> tables() {
    return Set.of("audit_incident_scope_retention_settings");
  }

  @Override
  public Map<String, String> keyLabels() {
    return Map.of("retentionYears", "Löschfrist in Kalenderjahren");
  }

  @Override
  public Set<String> securityRelevantKeys() {
    return Set.of("retentionYears");
  }

  @Override
  public Map<String, Object> export(CurrentUser actor) {
    AuditIncidentScopeRetentionSettings.Setting setting = settings.setting();
    Map<String, Object> values = new LinkedHashMap<>();
    values.put(
        "retentionYears",
        setting.overridden() ? setting.retention().incidentScopeRetentionYears() : null);
    return values;
  }

  @Override
  public void apply(CurrentUser actor, SettingsValues values) {
    settings.change(actor.organizationId(), actor.id(), values.optionalInt("retentionYears"));
  }
}
