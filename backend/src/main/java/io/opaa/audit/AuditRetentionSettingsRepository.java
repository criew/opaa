package io.opaa.audit;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for the singleton {@link AuditRetentionSettings} row and the entry point into the
 * {@code opaa_audit_delete_expired_partitions()} database function - the one and only call in this
 * codebase that can remove anything from {@code audit_log}, and the one this interface deliberately
 * exposes with no parameters, matching the function's own signature ("ein Aufruf, der einzelne
 * Sätze entfernen würde, existiert nicht").
 */
public interface AuditRetentionSettingsRepository
    extends JpaRepository<AuditRetentionSettings, Integer> {

  default Optional<AuditRetentionSettings> findSingleton() {
    return findById(AuditRetentionSettings.SINGLETON_ID);
  }

  /**
   * The only way this codebase changes {@code retention_months} - a native, explicitly narrow
   * {@code UPDATE} touching exactly {@code retention_months}/{@code updated_at}, matching the
   * application account's actual database grant. Deliberately not {@code JpaRepository#save} on the
   * read-only {@link AuditRetentionSettings} entity: Hibernate's default dirty-checked {@code
   * UPDATE} writes every mapped column regardless of which one logically changed, which would
   * include {@code last_cutoff}/{@code last_run_month} - columns the application account cannot
   * write - and fail with "permission denied for table" against the real, restricted grant.
   */
  @Modifying
  @Query(
      value =
          "UPDATE audit_retention_settings SET retention_months = :retentionMonths,"
              + " updated_at = now() WHERE id = 1",
      nativeQuery = true)
  int updateRetentionMonths(@Param("retentionMonths") int retentionMonths);

  /**
   * Invokes the parameterless deletion function and returns the names of the monthly partitions it
   * dropped in this call (possibly empty - nothing expired yet). Runs under whatever transaction
   * the caller ({@link AuditRetentionDeletionService}) has open; the function itself is {@code
   * SECURITY DEFINER}, so the actual {@code DROP TABLE} statements execute as {@code
   * opaa_audit_owner} regardless of this call happening over the application account's own
   * connection ("läuft nicht über das Anwendungskonto").
   */
  @Query(value = "SELECT * FROM opaa_audit_delete_expired_partitions()", nativeQuery = true)
  List<String> deleteExpiredPartitions();
}
