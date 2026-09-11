package io.opaa.auth.local;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Persistence for {@link LocalCredentials}; the id is the user's id. */
@Repository
public interface LocalCredentialsRepository extends JpaRepository<LocalCredentials, UUID> {

  /** The one bootstrap account ({@code ux_local_credentials_single_bootstrap}), if it exists. */
  Optional<LocalCredentials> findByBootstrapTrue();

  /**
   * Counts one failed sign-in atomically in the database ({@code n = n + 1}, ADR-0033 Entscheidung
   * 9) - never read-increment-write on a possibly stale entity, so concurrent failures never lose a
   * count. Returns the number of rows updated (0 when the user has no local credentials); the
   * caller reads the new value back to decide about the lock. Bypasses the optimistic {@code
   * version}, as every bulk update does.
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE LocalCredentials c SET c.failedLoginAttempts = c.failedLoginAttempts + 1,"
          + " c.updatedAt = :now WHERE c.userId = :userId")
  int recordFailedLogin(@Param("userId") UUID userId, @Param("now") Instant now);
}
