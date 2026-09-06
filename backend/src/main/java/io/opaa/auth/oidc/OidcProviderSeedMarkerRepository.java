package io.opaa.auth.oidc;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for the singleton {@link OidcProviderSeedMarker} row (#1329). */
@Repository
public interface OidcProviderSeedMarkerRepository
    extends JpaRepository<OidcProviderSeedMarker, Integer> {

  /** Whether {@link OidcProviderSeeder} has already attempted the one-time takeover. */
  default boolean seedAlreadyAttempted() {
    return existsById(OidcProviderSeedMarker.SINGLETON_ID);
  }
}
