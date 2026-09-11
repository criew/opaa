package io.opaa.auth.local;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for the singleton {@link LocalAdminSeedMarker} row (#1534). */
@Repository
public interface LocalAdminSeedMarkerRepository
    extends JpaRepository<LocalAdminSeedMarker, Integer> {

  /** Whether {@link LocalAdminSeeder} has already attempted the one-time seed. */
  default boolean seedAlreadyAttempted() {
    return existsById(LocalAdminSeedMarker.SINGLETON_ID);
  }
}
