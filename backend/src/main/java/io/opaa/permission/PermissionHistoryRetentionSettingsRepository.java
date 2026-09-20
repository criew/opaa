package io.opaa.permission;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence of the singleton {@link PermissionHistoryRetentionSettings} row. The two writes are
 * deliberately separate statements over disjoint columns: the configured period is an
 * administrative decision, {@code last_cutoff}/{@code last_run_month} are the deletion run's own
 * bookkeeping, and neither call site may overwrite the other's columns with a stale value.
 */
public interface PermissionHistoryRetentionSettingsRepository
    extends JpaRepository<PermissionHistoryRetentionSettings, Integer> {

  default Optional<PermissionHistoryRetentionSettings> findSingleton() {
    return findById(PermissionHistoryRetentionSettings.SINGLETON_ID);
  }

  /**
   * The settings row locked for the duration of the caller's transaction - what makes a deletion
   * run and a concurrent change of the period serialize against each other, so a run never deletes
   * against a period that was replaced while it was computing its cutoff.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from PermissionHistoryRetentionSettings s where s.id = 1")
  Optional<PermissionHistoryRetentionSettings> findSingletonForUpdate();

  /**
   * The only way this codebase changes the configured period. {@code clearAutomatically} matters
   * here: the caller reads the row before the update to record the previous value, so without
   * clearing, its own re-read afterwards would be served from the persistence context and return
   * the value that was just replaced.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "UPDATE permission_history_retention_settings SET retention_months = :retentionMonths,"
              + " updated_at = now() WHERE id = 1",
      nativeQuery = true)
  int updateRetentionMonths(@Param("retentionMonths") int retentionMonths);

  /** Records how far the last deletion run actually got; never touches {@code retention_months}. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "UPDATE permission_history_retention_settings SET last_cutoff = :cutoff,"
              + " last_run_month = :runMonth WHERE id = 1",
      nativeQuery = true)
  int recordRun(@Param("cutoff") Instant cutoff, @Param("runMonth") LocalDate runMonth);
}
