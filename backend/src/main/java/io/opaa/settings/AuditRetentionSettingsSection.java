package io.opaa.settings;

import io.opaa.audit.AuditRetentionSettingsService;
import io.opaa.auth.CurrentUser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** The retention period of the audit log; its run state ({@code last_cutoff}) is not a setting. */
@Component
class AuditRetentionSettingsSection implements SettingsSection {

  private static final String REASON = "Import der Einstellungen";

  private final AuditRetentionSettingsService service;

  AuditRetentionSettingsSection(AuditRetentionSettingsService service) {
    this.service = service;
  }

  @Override
  public String key() {
    return "auditRetention";
  }

  @Override
  public String label() {
    return "Aufbewahrungsfrist des Protokolls";
  }

  @Override
  public Set<String> tables() {
    return Set.of("audit_retention_settings");
  }

  @Override
  public Map<String, Object> export(CurrentUser actor) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("retentionMonths", service.currentRetentionMonths());
    return values;
  }

  @Override
  public void apply(CurrentUser actor, SettingsValues values) {
    service.updateRetention(
        actor.organizationId(), actor.id(), values.requireInt("retentionMonths"), REASON);
  }
}
