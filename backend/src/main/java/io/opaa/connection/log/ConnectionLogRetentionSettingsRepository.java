package io.opaa.connection.log;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The retention row. The update touches exactly the two columns the application account may write;
 * clearing the persistence context lets a re-read see the new value.
 */
interface ConnectionLogRetentionSettingsRepository
    extends JpaRepository<ConnectionLogRetentionSettings, Integer> {

  default Optional<ConnectionLogRetentionSettings> findSingleton() {
    return findById(ConnectionLogRetentionSettings.SINGLETON_ID);
  }

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "UPDATE connection_log_retention_settings SET retention_months = :retentionMonths,"
              + " updated_at = now() WHERE id = 1",
      nativeQuery = true)
  int updateRetentionMonths(@Param("retentionMonths") int retentionMonths);
}
