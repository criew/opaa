package io.opaa.audit;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The one row of {@link AuditIncidentScopeRetentionSetting}, or none. */
interface AuditIncidentScopeRetentionSettingRepository
    extends JpaRepository<AuditIncidentScopeRetentionSetting, Integer> {

  default Optional<AuditIncidentScopeRetentionSetting> findSingleton() {
    return findById(AuditIncidentScopeRetentionSetting.SINGLETON_ID);
  }

  /** Sets the period, creating the row on first use. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "INSERT INTO audit_incident_scope_retention_settings (id, retention_years, updated_at)"
              + " VALUES (1, :retentionYears, now())"
              + " ON CONFLICT (id) DO UPDATE SET retention_years = EXCLUDED.retention_years,"
              + " updated_at = now()",
      nativeQuery = true)
  int upsert(@Param("retentionYears") int retentionYears);

  /** Removes the row, so that the factory default applies again. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value = "DELETE FROM audit_incident_scope_retention_settings WHERE id = 1",
      nativeQuery = true)
  int clear();
}
