package io.opaa.audit;

import io.opaa.compliance.InstanceDisclosure.StoredValue;
import io.opaa.compliance.InstanceStateSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The retention of the audit log as this instance holds it and whether the audit log goes to a
 * SIEM, for the disclosure.
 */
@Component
class AuditInstanceState implements InstanceStateSource {

  static final String INCIDENT_SCOPE_RETENTION_KEY =
      "audit_incident_scope_retention_settings.retention_years";

  private final AuditRetentionSettingsRepository repository;
  private final AuditIncidentScopeRetentionSettingRepository incidentScopeRetention;
  private final SiemExportProperties siemExport;

  AuditInstanceState(
      AuditRetentionSettingsRepository repository,
      AuditIncidentScopeRetentionSettingRepository incidentScopeRetention,
      SiemExportProperties siemExport) {
    this.repository = repository;
    this.incidentScopeRetention = incidentScopeRetention;
    this.siemExport = siemExport;
  }

  @Override
  public Map<String, Boolean> dataFlows(UUID organizationId) {
    return Map.of("siem-export", siemExport.enabled());
  }

  /** The retention of the incident scopes without a row is the factory default, without a time. */
  @Override
  public Map<String, StoredValue> storedSettings() {
    Map<String, StoredValue> values = new LinkedHashMap<>();
    repository
        .findSingleton()
        .ifPresent(
            settings ->
                values.put(
                    "audit_retention_settings.retention_months",
                    new StoredValue(
                        Integer.toString(settings.getRetentionMonths()), settings.getUpdatedAt())));
    values.put(
        INCIDENT_SCOPE_RETENTION_KEY,
        incidentScopeRetention
            .findSingleton()
            .map(
                row ->
                    new StoredValue(
                        Integer.toString(row.retention().incidentScopeRetentionYears()),
                        row.getUpdatedAt()))
            .orElseGet(
                () ->
                    new StoredValue(
                        Integer.toString(AuditIncidentScopeRetention.DEFAULT_YEARS), null)));
    return values;
  }
}
