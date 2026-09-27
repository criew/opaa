package io.opaa.account;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for the singleton {@link LocalAuthSettings} row, seeded by the baseline. */
@Repository
public interface LocalAuthSettingsRepository extends JpaRepository<LocalAuthSettings, Integer> {

  default Optional<LocalAuthSettings> findSingleton() {
    return findById(LocalAuthSettings.SINGLETON_ID);
  }
}
