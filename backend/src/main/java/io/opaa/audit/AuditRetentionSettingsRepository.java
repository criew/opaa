package io.opaa.audit;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Persistence for the singleton {@link AuditRetentionSettings} row and the entry point into the
 * {@code opaa_audit_delete_expired_partitions()} database function (migration 023) - the one and
 * only call in this codebase that can remove anything from {@code audit_log}, and the one this
 * interface deliberately exposes with no parameters, matching the function's own signature (#395
 * acceptance criteria: "ein Aufruf, der einzelne Sätze entfernen würde, existiert nicht").
 */
public interface AuditRetentionSettingsRepository
    extends JpaRepository<AuditRetentionSettings, Integer> {

  default Optional<AuditRetentionSettings> findSingleton() {
    return findById(AuditRetentionSettings.SINGLETON_ID);
  }

  /**
   * Invokes the parameterless deletion function and returns the names of the monthly partitions it
   * dropped in this call (possibly empty - nothing expired yet). Runs under whatever transaction
   * the caller ({@link AuditRetentionDeletionService}) has open; the function itself is {@code
   * SECURITY DEFINER}, so the actual {@code DROP TABLE} statements execute as {@code
   * opaa_audit_owner} regardless of this call happening over the application account's own
   * connection - see migration 023's comment for why that satisfies "läuft nicht über das
   * Anwendungskonto".
   */
  @Query(value = "SELECT * FROM opaa_audit_delete_expired_partitions()", nativeQuery = true)
  List<String> deleteExpiredPartitions();
}
