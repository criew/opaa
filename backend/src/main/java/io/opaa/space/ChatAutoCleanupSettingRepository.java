package io.opaa.space;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The one row of {@link ChatAutoCleanupSetting}, or none. */
interface ChatAutoCleanupSettingRepository extends JpaRepository<ChatAutoCleanupSetting, Integer> {

  default Optional<ChatAutoCleanupSetting> findSingleton() {
    return findById(ChatAutoCleanupSetting.SINGLETON_ID);
  }

  /** Sets both periods, creating the row on first use. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "INSERT INTO chat_auto_cleanup_settings"
              + " (id, archive_after_days, delete_after_days, updated_at)"
              + " VALUES (1, :archiveAfterDays, :deleteAfterDays, now())"
              + " ON CONFLICT (id) DO UPDATE SET archive_after_days = EXCLUDED.archive_after_days,"
              + " delete_after_days = EXCLUDED.delete_after_days, updated_at = now()",
      nativeQuery = true)
  int upsert(
      @Param("archiveAfterDays") int archiveAfterDays,
      @Param("deleteAfterDays") int deleteAfterDays);

  /** Removes the row, so that the factory defaults apply again. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(value = "DELETE FROM chat_auto_cleanup_settings WHERE id = 1", nativeQuery = true)
  int clear();
}
