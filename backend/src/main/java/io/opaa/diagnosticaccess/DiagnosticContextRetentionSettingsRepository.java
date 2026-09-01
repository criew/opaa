package io.opaa.diagnosticaccess;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The singleton retention row plus the entry point into {@code
 * opaa_diagnostic_context_delete_expired_partitions()} - the only call in this codebase that can
 * remove anything from {@code diagnostic_context_log}, deliberately parameterless so no caller can
 * narrow or widen what a single run removes.
 */
public interface DiagnosticContextRetentionSettingsRepository
    extends JpaRepository<DiagnosticContextRetentionSettings, Integer> {

  default Optional<DiagnosticContextRetentionSettings> findSingleton() {
    return findById(DiagnosticContextRetentionSettings.SINGLETON_ID);
  }

  /**
   * Narrow native update touching exactly the two columns the application account holds {@code
   * UPDATE} on - same reasoning as {@code AuditRetentionSettingsRepository#updateRetentionMonths}.
   */
  @Modifying
  @Query(
      value =
          "UPDATE diagnostic_context_retention_settings SET retention_months = :retentionMonths,"
              + " updated_at = now() WHERE id = 1",
      nativeQuery = true)
  int updateRetentionMonths(@Param("retentionMonths") int retentionMonths);

  /** Drops every fully expired monthly partition and returns their names, possibly none. */
  @Query(
      value = "SELECT * FROM opaa_diagnostic_context_delete_expired_partitions()",
      nativeQuery = true)
  List<String> deleteExpiredPartitions();
}
