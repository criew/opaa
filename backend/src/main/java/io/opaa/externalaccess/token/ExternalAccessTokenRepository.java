package io.opaa.externalaccess.token;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ExternalAccessTokenRepository extends JpaRepository<ExternalAccessToken, UUID> {

  /** The admin lists' predicate: the token's person belongs to {@code :organizationId}. */
  String OWN_ORGANIZATION =
      " t.userId IN (SELECT u.id FROM User u WHERE u.organizationId = :organizationId)";

  /** The one lookup of the per-call check; the hash column is unique. */
  Optional<ExternalAccessToken> findByTokenLookupHash(String tokenLookupHash);

  List<ExternalAccessToken> findByUserIdOrderByCreatedAtDesc(UUID userId);

  Optional<ExternalAccessToken> findByIdAndUserId(UUID id, UUID userId);

  List<ExternalAccessToken> findByUserIdAndRevokedAtIsNull(UUID userId);

  /**
   * The token, its row locked until the caller's transaction ends - so that of two runs recording
   * its lapse, the second reads the marker the first committed.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT t FROM ExternalAccessToken t WHERE t.id = :id")
  Optional<ExternalAccessToken> findForLapse(@Param("id") UUID id);

  /**
   * The unfiltered Bestandsliste of one organization, soonest expiry first. The order is fixed and
   * there is no ordering parameter: a list that could be sorted freely could be sorted by person.
   * Every list below is limited to the tokens of persons of {@code organizationId}.
   */
  @Query(
      "SELECT t FROM ExternalAccessToken t WHERE" + OWN_ORGANIZATION + " ORDER BY t.expiresAt ASC")
  List<ExternalAccessToken> findForAdminList(
      @Param("organizationId") UUID organizationId, Pageable page);

  /** Still live at {@code now}. */
  @Query(
      "SELECT t FROM ExternalAccessToken t WHERE t.revokedAt IS NULL AND t.expiresAt > :now AND"
          + OWN_ORGANIZATION
          + " ORDER BY t.expiresAt ASC")
  List<ExternalAccessToken> findActiveForAdminList(
      @Param("organizationId") UUID organizationId, @Param("now") Instant now, Pageable page);

  /**
   * Run out - either already marked by the daily run or simply past its expiry, which the derived
   * state treats alike.
   */
  @Query(
      "SELECT t FROM ExternalAccessToken t WHERE"
          + " (t.revocationReason = io.opaa.externalaccess.token"
          + ".ExternalAccessTokenRevocationReason.EXPIRED"
          + " OR (t.revokedAt IS NULL AND t.expiresAt <= :now)) AND"
          + OWN_ORGANIZATION
          + " ORDER BY t.expiresAt ASC")
  List<ExternalAccessToken> findExpiredForAdminList(
      @Param("organizationId") UUID organizationId, @Param("now") Instant now, Pageable page);

  /** Withdrawn for one of the given reasons - the person's own revocation, or a block. */
  @Query(
      "SELECT t FROM ExternalAccessToken t WHERE t.revocationReason IN :reasons AND"
          + OWN_ORGANIZATION
          + " ORDER BY t.expiresAt ASC")
  List<ExternalAccessToken> findByRevocationReasonsForAdminList(
      @Param("organizationId") UUID organizationId,
      @Param("reasons") Collection<ExternalAccessTokenRevocationReason> reasons,
      Pageable page);

  /** Still live and running out inside the horizon - the "läuft demnächst ab"-Filter. */
  @Query(
      "SELECT t FROM ExternalAccessToken t WHERE t.revokedAt IS NULL"
          + " AND t.expiresAt > :now AND t.expiresAt <= :horizon AND"
          + OWN_ORGANIZATION
          + " ORDER BY t.expiresAt ASC")
  List<ExternalAccessToken> findExpiringWithinForAdminList(
      @Param("organizationId") UUID organizationId,
      @Param("now") Instant now,
      @Param("horizon") Instant horizon,
      Pageable page);

  /** Tokens that have run out but whose Ausserkrafttreten has not been recorded yet. */
  @Query(
      "SELECT t FROM ExternalAccessToken t WHERE t.lapseRecordedAt IS NULL"
          + " AND (t.revokedAt IS NOT NULL OR t.expiresAt <= :now)")
  List<ExternalAccessToken> findLapsed(@Param("now") Instant now);

  /** Live tokens whose expiry falls into the half-open day window of a reminder. */
  @Query(
      "SELECT t FROM ExternalAccessToken t WHERE t.revokedAt IS NULL"
          + " AND t.expiresAt >= :from AND t.expiresAt < :to")
  List<ExternalAccessToken> findExpiringBetween(
      @Param("from") Instant from, @Param("to") Instant to);

  /**
   * Claims the reminder {@code leadDays} before the token's expiry: true for exactly one call per
   * lead time, whichever instance makes it, and never for a longer lead time after a shorter one.
   * The column is not mapped, so saving a stale entity never undoes the claim.
   *
   * @return 1 when this call claimed it
   */
  @Transactional
  @Modifying
  @Query(
      value =
          "UPDATE external_access_tokens SET expiry_reminded_days = :leadDays WHERE id = :id"
              + " AND (expiry_reminded_days IS NULL OR expiry_reminded_days > :leadDays)",
      nativeQuery = true)
  int claimExpiryReminder(@Param("id") UUID id, @Param("leadDays") int leadDays);

  /**
   * Removes dead rows whose retention has run out. Bounded by {@code lapseRecordedAt} rather than
   * by the expiry itself: the clock of the Loeschfrist starts when the token stopped working, and
   * the row must outlive nothing but its own audit entry.
   */
  @Modifying
  @Query(
      "DELETE FROM ExternalAccessToken t WHERE t.lapseRecordedAt IS NOT NULL"
          + " AND t.lapseRecordedAt < :cutoff")
  int deleteLapsedBefore(@Param("cutoff") Instant cutoff);

  /** How many tokens name this library in their selection - a count, never a person. */
  @Query(
      "SELECT COUNT(t) FROM ExternalAccessToken t JOIN t.libraries l"
          + " WHERE l.libraryId = :libraryId AND l.extinguishedAt IS NULL"
          + " AND t.revokedAt IS NULL AND t.expiresAt > :now")
  long countActiveContainingLibrary(@Param("libraryId") UUID libraryId, @Param("now") Instant now);
}
