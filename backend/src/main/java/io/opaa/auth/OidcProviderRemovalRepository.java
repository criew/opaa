package io.opaa.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for {@link OidcProviderRemoval}; {@code save} replaces the row of its issuer. */
@Repository
public interface OidcProviderRemovalRepository extends JpaRepository<OidcProviderRemoval, String> {}
