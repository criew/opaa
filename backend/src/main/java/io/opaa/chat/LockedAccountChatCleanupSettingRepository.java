package io.opaa.chat;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The one row of {@link LockedAccountChatCleanupSetting}, or none. */
interface LockedAccountChatCleanupSettingRepository
    extends JpaRepository<LockedAccountChatCleanupSetting, Integer> {

  default Optional<LockedAccountChatCleanupSetting> findSingleton() {
    return findById(LockedAccountChatCleanupSetting.SINGLETON_ID);
  }

  /** Sets switch and period, creating the row on first use. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "INSERT INTO locked_account_chat_cleanup_settings"
              + " (id, enabled, delete_after_days, updated_at)"
              + " VALUES (1, :enabled, :deleteAfterDays, now())"
              + " ON CONFLICT (id) DO UPDATE SET enabled = EXCLUDED.enabled,"
              + " delete_after_days = EXCLUDED.delete_after_days, updated_at = now()",
      nativeQuery = true)
  int upsert(@Param("enabled") boolean enabled, @Param("deleteAfterDays") int deleteAfterDays);

  /** Removes the row, so that the factory defaults apply again. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value = "DELETE FROM locked_account_chat_cleanup_settings WHERE id = 1",
      nativeQuery = true)
  int clear();
}
