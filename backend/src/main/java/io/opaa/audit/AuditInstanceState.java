package io.opaa.audit;

import io.opaa.compliance.InstanceDisclosure.StoredValue;
import io.opaa.compliance.InstanceStateSource;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The retention of the audit log as this instance holds it and whether the audit log goes to a
 * SIEM, for the disclosure.
 */
@Component
class AuditInstanceState implements InstanceStateSource {

  private final AuditRetentionSettingsRepository repository;
  private final SiemExportProperties siemExport;

  AuditInstanceState(AuditRetentionSettingsRepository repository, SiemExportProperties siemExport) {
    this.repository = repository;
    this.siemExport = siemExport;
  }

  @Override
  public Map<String, Boolean> dataFlows(UUID organizationId) {
    return Map.of("siem-export", siemExport.enabled());
  }

  @Override
  public Map<String, StoredValue> storedSettings() {
    return repository
        .findSingleton()
        .map(
            settings ->
                Map.of(
                    "audit_retention_settings.retention_months",
                    new StoredValue(
                        Integer.toString(settings.getRetentionMonths()), settings.getUpdatedAt())))
        .orElse(Map.of());
  }
}
