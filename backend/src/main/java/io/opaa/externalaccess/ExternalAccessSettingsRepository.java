package io.opaa.externalaccess;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for the singleton {@link ExternalAccessSettings} row, seeded by changeset 032. */
@Repository
public interface ExternalAccessSettingsRepository
    extends JpaRepository<ExternalAccessSettings, Integer> {

  default Optional<ExternalAccessSettings> findSingleton() {
    return findById(ExternalAccessSettings.SINGLETON_ID);
  }
}
