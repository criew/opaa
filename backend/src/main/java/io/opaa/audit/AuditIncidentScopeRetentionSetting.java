package io.opaa.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The period the grants of the anlassbezogene Klärung are kept, as the system administration set
 * it; without the row {@link AuditIncidentScopeRetention#defaults()} applies. Written only through
 * {@link AuditIncidentScopeRetentionSettingRepository#upsert}.
 */
@Entity
@Table(name = "audit_incident_scope_retention_settings")
class AuditIncidentScopeRetentionSetting {

  static final int SINGLETON_ID = 1;

  @Id private Integer id;

  @Column(name = "retention_years", insertable = false, updatable = false)
  private int retentionYears;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private Instant updatedAt;

  protected AuditIncidentScopeRetentionSetting() {}

  AuditIncidentScopeRetention retention() {
    return new AuditIncidentScopeRetention(retentionYears);
  }

  Instant getUpdatedAt() {
    return updatedAt;
  }
}
