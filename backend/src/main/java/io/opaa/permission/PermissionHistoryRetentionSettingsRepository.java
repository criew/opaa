package io.opaa.permission;

import jakarta.persistence.LockModeType;
import java.time.Instant;
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
  default Optional<PermissionHistoryRetentionSettings> findSingletonForUpdate() {
    return findByIdForUpdate(PermissionHistoryRetentionSettings.SINGLETON_ID);
  }

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from PermissionHistoryRetentionSettings s where s.id = :id")
  Optional<PermissionHistoryRetentionSettings> findByIdForUpdate(@Param("id") int id);

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

  /**
   * Records how far the deletion has got; never touches {@code retention_months}. The value only
   * ever moves forward - {@code PermissionHistoryRetentionDeletionService} is the one caller and
   * decides that.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value = "UPDATE permission_history_retention_settings SET last_cutoff = :cutoff WHERE id = 1",
      nativeQuery = true)
  int recordProgress(@Param("cutoff") Instant cutoff);
}
