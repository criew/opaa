package io.opaa.auth.oidc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

  /**
   * The provider whose issuer equals {@code normalizedIssuerUri} after stripping trailing slashes -
   * the lookup key of {@link OidcIssuerUris#normalize}, which the caller applies to its argument;
   * the stored value itself keeps the provider's spelling.
   */
  @Query("select p from OidcProvider p where trim(trailing '/' from p.issuerUri) = :issuer")
  Optional<OidcProvider> findByNormalizedIssuerUri(@Param("issuer") String normalizedIssuerUri);

  /** The one default provider, if any ({@code ux_oidc_providers_single_default}). */
  Optional<OidcProvider> findByDefaultProviderTrue();
}
