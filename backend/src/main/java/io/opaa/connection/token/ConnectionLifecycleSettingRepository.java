package io.opaa.connection.token;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The rows of {@link ConnectionLifecycleSettingRow}, at most one per setting. */
public interface ConnectionLifecycleSettingRepository
    extends JpaRepository<ConnectionLifecycleSettingRow, String> {

  /** The value the administration set for {@code setting}, read from the table on every call. */
  @Query(
      value = "SELECT setting_value FROM connection_lifecycle_settings WHERE setting = :setting",
      nativeQuery = true)
  Optional<Integer> findValue(@Param("setting") String setting);

  /** Sets the value of {@code setting}, creating its row on first use. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "INSERT INTO connection_lifecycle_settings (setting, setting_value, updated_at)"
              + " VALUES (:setting, :value, now())"
              + " ON CONFLICT (setting) DO UPDATE SET setting_value = EXCLUDED.setting_value,"
              + " updated_at = now()",
      nativeQuery = true)
  int upsert(@Param("setting") String setting, @Param("value") int value);

  /** Removes the value of {@code setting}, so that the factory default applies again. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value = "DELETE FROM connection_lifecycle_settings WHERE setting = :setting",
      nativeQuery = true)
  int clear(@Param("setting") String setting);
}
