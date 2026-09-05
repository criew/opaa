package io.opaa.auth.oidc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for {@link OidcProvider} (#1329). */
@Repository
public interface OidcProviderRepository extends JpaRepository<OidcProvider, UUID> {

  /** Every provider in the order the sign-in page shows them. */
  List<OidcProvider> findAllByOrderBySortOrderAscDisplayNameAsc();

  /**
   * The providers a token may come from - what {@link OidcProviderRegistry} builds decoders for.
   */
  List<OidcProvider> findAllByEnabledTrueOrderBySortOrderAscDisplayNameAsc();

  /** Exact match on the normalized issuer (see {@link OidcIssuerUris#normalize}). */
  Optional<OidcProvider> findByIssuerUri(String issuerUri);

  /** The one default provider, if any ({@code ux_oidc_providers_single_default}). */
  Optional<OidcProvider> findByDefaultProviderTrue();
}
