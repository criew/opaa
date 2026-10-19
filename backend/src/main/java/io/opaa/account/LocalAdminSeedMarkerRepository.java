package io.opaa.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** Persistence for the singleton {@link LocalAdminSeedMarker} row. */
@Repository
public interface LocalAdminSeedMarkerRepository
    extends JpaRepository<LocalAdminSeedMarker, Integer> {

  /** Whether {@link LocalAdminSeeder} has already attempted the one-time seed. */
  default boolean seedAlreadyAttempted() {
    return existsById(LocalAdminSeedMarker.SINGLETON_ID);
  }

  /**
   * Holds the start-up seed lock until the transaction ends: the seeds of instances that start
   * together run one after another, so a later one sees what an earlier one committed. Namespace
   * {@code 209}, shared by every start-up seed.
   */
  @Query(
      value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(209, 0)) acquired",
      nativeQuery = true)
  int lockStartupSeeds();
}
