package io.opaa.knowledge;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The one row of {@link SharedLibraryQuotaSetting}, or none. */
public interface SharedLibraryQuotaSettingRepository
    extends JpaRepository<SharedLibraryQuotaSetting, Integer> {

  default Optional<SharedLibraryQuotaSetting> findSingleton() {
    return findById(SharedLibraryQuotaSetting.SINGLETON_ID);
  }

  /** Sets the value, creating the row on first use. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "INSERT INTO shared_library_quota_settings (id, quota_bytes, updated_at)"
              + " VALUES (1, :quotaBytes, now())"
              + " ON CONFLICT (id) DO UPDATE SET quota_bytes = EXCLUDED.quota_bytes,"
              + " updated_at = now()",
      nativeQuery = true)
  int upsert(@Param("quotaBytes") long quotaBytes);

  /** Removes the value, so that the operator's upper limit applies alone again. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(value = "DELETE FROM shared_library_quota_settings WHERE id = 1", nativeQuery = true)
  int clear();
}
