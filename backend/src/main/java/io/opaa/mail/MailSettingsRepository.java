package io.opaa.mail;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for the singleton {@link MailSettings} row (#1536). */
@Repository
public interface MailSettingsRepository extends JpaRepository<MailSettings, Integer> {

  /** The one row; created by changeset 011-mail-settings, never by application code. */
  default Optional<MailSettings> findSingleton() {
    return findById(MailSettings.SINGLETON_ID);
  }
}
