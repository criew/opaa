package io.opaa.indexing.spi;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The rows of {@link FullSyncRhythmSetting}, at most one per source type. */
public interface FullSyncRhythmSettingRepository
    extends JpaRepository<FullSyncRhythmSetting, String> {

  /** The days the administration set for {@code sourceType}, read from the table on every call. */
  @Query(
      value = "SELECT interval_days FROM full_sync_rhythm_settings WHERE source_type = :sourceType",
      nativeQuery = true)
  Optional<Integer> findDays(@Param("sourceType") String sourceType);

  /** Sets the days of {@code sourceType}, creating its row on first use. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "INSERT INTO full_sync_rhythm_settings (source_type, interval_days, updated_at)"
              + " VALUES (:sourceType, :days, now())"
              + " ON CONFLICT (source_type) DO UPDATE SET interval_days = EXCLUDED.interval_days,"
              + " updated_at = now()",
      nativeQuery = true)
  int upsert(@Param("sourceType") String sourceType, @Param("days") int days);

  /** Removes the value of {@code sourceType}, so that the factory default applies again. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value = "DELETE FROM full_sync_rhythm_settings WHERE source_type = :sourceType",
      nativeQuery = true)
  int clear(@Param("sourceType") String sourceType);
}
