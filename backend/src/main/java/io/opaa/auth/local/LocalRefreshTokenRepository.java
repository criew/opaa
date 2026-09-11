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

/**
 * Persistence for {@link LocalRefreshToken}. The revocations are single atomic {@code UPDATE}s
 * decided by the database - a reuse detection must never lose a race with a concurrent refresh -
 * and return the number of rows they revoked.
 */
@Repository
public interface LocalRefreshTokenRepository extends JpaRepository<LocalRefreshToken, UUID> {

  Optional<LocalRefreshToken> findByTokenLookupHash(String tokenLookupHash);

  /** Revokes every still active token of the family; 0 when none was active. */
  @Transactional
  @Modifying
  @Query(
      "UPDATE LocalRefreshToken t SET t.revokedAt = :now, t.revocationReason = :reason"
          + " WHERE t.familyId = :familyId AND t.revokedAt IS NULL")
  int revokeFamily(
      @Param("familyId") UUID familyId,
      @Param("reason") RevocationReason reason,
      @Param("now") Instant now);

  /** Revokes every still active token of the user - lock, reset, handover. */
  @Transactional
  @Modifying
  @Query(
      "UPDATE LocalRefreshToken t SET t.revokedAt = :now, t.revocationReason = :reason"
          + " WHERE t.userId = :userId AND t.revokedAt IS NULL")
  int revokeAllForUser(
      @Param("userId") UUID userId,
      @Param("reason") RevocationReason reason,
      @Param("now") Instant now);

  /**
   * Removes rows whose idle limit, family end or revocation lies before {@code cutoff} - the
   * cleanup run's "at most seven days after expiry or revocation" (ADR-0033, Entscheidung 7).
   */
  @Transactional
  @Modifying
  @Query(
      "DELETE FROM LocalRefreshToken t WHERE t.expiresAt < :cutoff"
          + " OR t.familyExpiresAt < :cutoff OR t.revokedAt < :cutoff")
  int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
