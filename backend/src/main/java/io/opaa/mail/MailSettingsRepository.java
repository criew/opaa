package io.opaa.mail;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persistence for the singleton {@link MailSettings} row. */
@Repository
public interface MailSettingsRepository extends JpaRepository<MailSettings, Integer> {

  /** The one row; created by the baseline, never by application code. */
  default Optional<MailSettings> findSingleton() {
    return findById(MailSettings.SINGLETON_ID);
  }

  /**
   * Records a successful send in the status column only. The failure fields are deliberately kept:
   * "zuletzt erfolgreich" and "zuletzt fehlgeschlagen" answer different questions, and clearing the
   * older one would hide that mail is failing intermittently.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update MailSettings m set m.lastSuccessAt = :at where m.id = 1")
  int recordSuccess(@Param("at") Instant at);

  /** Records a failed send with its cause in the status columns only. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update MailSettings m set m.lastFailureAt = :at, m.lastFailureReason = :reason"
          + " where m.id = 1")
  int recordFailure(@Param("at") Instant at, @Param("reason") String reason);
}
