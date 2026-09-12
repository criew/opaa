package io.opaa.auth.local;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
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

  /**
   * The creation reason alone - the one field {@code GET /api/v1/auth/me} needs (ADR-0033,
   * Entscheidung 11). A projection rather than {@code findById}: loading the whole row would pull
   * the password hash and the lock state into a response path that has no business with either.
   */
  @Query("SELECT c.createdReason FROM LocalCredentials c WHERE c.userId = :userId")
  Optional<String> findCreatedReasonByUserId(@Param("userId") UUID userId);

  /** The one bootstrap account ({@code ux_local_credentials_single_bootstrap}), if it exists. */
  Optional<LocalCredentials> findByBootstrapTrue();

  /** The accounts expiring in {@code [from, to)} - the expiry reminder's window (#1537). */
  List<LocalCredentials> findByExpiresAtGreaterThanEqualAndExpiresAtLessThan(
      Instant from, Instant to);

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

  /**
   * The counterpart of {@link #recordFailedLogin} on a successful sign-in: the counter returns to
   * zero atomically, so a concurrent failed attempt neither loses its count nor makes the
   * successful sign-in fail on a stale {@code version}.
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE LocalCredentials c SET c.failedLoginAttempts = 0, c.updatedAt = :now"
          + " WHERE c.userId = :userId AND c.failedLoginAttempts <> 0")
  int resetFailedLoginAttempts(@Param("userId") UUID userId, @Param("now") Instant now);

  /**
   * The mass revocation of several accounts in one statement (switching the local account
   * management off, ADR-0033 Entscheidung 4): every access token of {@code userIds} issued before
   * {@code cutoff} becomes invalid. Bypasses the optimistic {@code version}, as every bulk update
   * does; returns the number of rows written.
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE LocalCredentials c SET c.passwordInvalidatedBefore = :cutoff, c.updatedAt = :now"
          + " WHERE c.userId IN :userIds")
  int invalidateSessionsIssuedBefore(
      @Param("userIds") Collection<UUID> userIds,
      @Param("cutoff") Instant cutoff,
      @Param("now") Instant now);
}
