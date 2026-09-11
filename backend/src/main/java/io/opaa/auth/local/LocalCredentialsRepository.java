package io.opaa.auth.local;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for {@link LocalCredentials}; the id is the user's id. */
@Repository
public interface LocalCredentialsRepository extends JpaRepository<LocalCredentials, UUID> {

  /** The one bootstrap account ({@code ux_local_credentials_single_bootstrap}), if it exists. */
  Optional<LocalCredentials> findByBootstrapTrue();
}
