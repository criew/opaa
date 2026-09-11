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
 * Persistence for {@link LocalRefreshToken}. Rotation and revocation are single atomic {@code
 * UPDATE}s decided by the database and return the number of rows they changed: two concurrent
 * refreshes with the same cookie race on {@link #rotateIfActive}, exactly one wins, and the loser
 * (0 rows) is a reuse the caller answers with {@link #revokeFamily}. The bulk updates clear and
 * flush the persistence context so a service holding entities of the same rows never reads stale
 * state afterwards.
 */
@Repository
public interface LocalRefreshTokenRepository extends JpaRepository<LocalRefreshToken, UUID> {

  Optional<LocalRefreshToken> findByTokenLookupHash(String tokenLookupHash);

  /**
   * The most recently revoked token of the user - its reason is the act that ended the sessions,
   * which a refused access token names as the cause of {@code session_revoked} (ADR-0033,
   * Entscheidung 8).
   */
  Optional<LocalRefreshToken> findFirstByUserIdAndRevokedAtIsNotNullOrderByRevokedAtDesc(
      UUID userId);

  /**
   * Rotates the token to its already inserted successor if - and only if - it is still active at
   * {@code now}: not revoked, within its idle limit and within the family's absolute end. Returns 1
   * for the one caller that won the rotation and 0 for every other, who must treat the presented
   * token as reused (ADR-0033, Entscheidung 7).
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE LocalRefreshToken t SET t.revokedAt = :now, t.revocationReason = :reason,"
          + " t.rotatedToId = :successorId WHERE t.id = :id AND t.revokedAt IS NULL"
          + " AND t.expiresAt > :now AND t.familyExpiresAt > :now")
  int rotateIfActive(
      @Param("id") UUID id,
      @Param("successorId") UUID successorId,
      @Param("reason") RevocationReason reason,
      @Param("now") Instant now);

  /** Revokes every still active token of the family; 0 when none was active. */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE LocalRefreshToken t SET t.revokedAt = :now, t.revocationReason = :reason"
          + " WHERE t.familyId = :familyId AND t.revokedAt IS NULL")
  int revokeFamily(
      @Param("familyId") UUID familyId,
      @Param("reason") RevocationReason reason,
      @Param("now") Instant now);

  /** Revokes every still active token of the user - lock, reset, handover. */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE LocalRefreshToken t SET t.revokedAt = :now, t.revocationReason = :reason"
          + " WHERE t.userId = :userId AND t.revokedAt IS NULL")
  int revokeAllForUser(
      @Param("userId") UUID userId,
      @Param("reason") RevocationReason reason,
      @Param("now") Instant now);

  /**
   * Revokes every still active token of the user except those of {@code familyId} - the password
   * change that keeps the session it was made from (ADR-0033, Entscheidung 11).
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE LocalRefreshToken t SET t.revokedAt = :now, t.revocationReason = :reason"
          + " WHERE t.userId = :userId AND t.familyId <> :familyId AND t.revokedAt IS NULL")
  int revokeAllForUserExceptFamily(
      @Param("userId") UUID userId,
      @Param("familyId") UUID familyId,
      @Param("reason") RevocationReason reason,
      @Param("now") Instant now);

  /**
   * Removes rows whose idle limit, family end or revocation lies before {@code cutoff} - the
   * cleanup run's "at most seven days after expiry or revocation" (ADR-0033, Entscheidung 7).
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "DELETE FROM LocalRefreshToken t WHERE t.expiresAt < :cutoff"
          + " OR t.familyExpiresAt < :cutoff OR t.revokedAt < :cutoff")
  int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
