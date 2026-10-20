package io.opaa.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** Persistence for the singleton {@link OidcProviderSeedMarker} row. */
@Repository
public interface OidcProviderSeedMarkerRepository
    extends JpaRepository<OidcProviderSeedMarker, Integer> {

  /** Whether {@link OidcProviderSeeder} has already attempted the one-time takeover. */
  default boolean seedAlreadyAttempted() {
    return existsById(OidcProviderSeedMarker.SINGLETON_ID);
  }

  /**
   * Holds the start-up seed lock until the transaction ends: the seeds of instances that start
   * together run one after another, so a later one sees what an earlier one committed. Namespace
   * {@code 211}, shared by every start-up seed.
   */
  @Query(
      value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(211, 0)) acquired",
      nativeQuery = true)
  int lockStartupSeeds();
}
