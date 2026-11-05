package io.opaa.externalaccess.token;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ServiceAccountTokenRepository extends JpaRepository<ServiceAccountToken, UUID> {

  /** The admin lists' predicate: the token's service account belongs to {@code :organizationId}. */
  String OWN_ORGANIZATION =
      " t.serviceAccountId IN (SELECT s.id FROM ServiceAccount s"
          + " WHERE s.organizationId = :organizationId)";

  /** The one lookup of the per-call check; the hash column is unique. */
  Optional<ServiceAccountToken> findByTokenLookupHash(String tokenLookupHash);

  List<ServiceAccountToken> findByServiceAccountIdOrderByCreatedAtDesc(UUID serviceAccountId);

  List<ServiceAccountToken> findByServiceAccountIdIn(Collection<UUID> serviceAccountIds);

  Optional<ServiceAccountToken> findByIdAndServiceAccountId(UUID id, UUID serviceAccountId);

  List<ServiceAccountToken> findByServiceAccountIdAndRevokedAtIsNull(UUID serviceAccountId);

  /** The account a token belongs to - what the quota and the alarm need from a token id. */
  @Query(
      "SELECT s FROM ServiceAccount s, ServiceAccountToken t"
          + " WHERE t.id = :tokenId AND s.id = t.serviceAccountId")
  Optional<ServiceAccount> findAccountOfToken(@Param("tokenId") UUID tokenId);

  @Query(
      "SELECT t FROM ServiceAccountToken t WHERE" + OWN_ORGANIZATION + " ORDER BY t.expiresAt ASC")
  List<ServiceAccountToken> findForAdminList(
      @Param("organizationId") UUID organizationId, Pageable page);

  @Query(
      "SELECT t FROM ServiceAccountToken t WHERE t.revokedAt IS NULL AND t.expiresAt > :now AND"
          + OWN_ORGANIZATION
          + " ORDER BY t.expiresAt ASC")
  List<ServiceAccountToken> findActiveForAdminList(
      @Param("organizationId") UUID organizationId, @Param("now") Instant now, Pageable page);

  @Query(
      "SELECT t FROM ServiceAccountToken t WHERE"
          + " (t.revocationReason = io.opaa.externalaccess.token"
          + ".ExternalAccessTokenRevocationReason.EXPIRED"
          + " OR (t.revokedAt IS NULL AND t.expiresAt <= :now)) AND"
          + OWN_ORGANIZATION
          + " ORDER BY t.expiresAt ASC")
  List<ServiceAccountToken> findExpiredForAdminList(
      @Param("organizationId") UUID organizationId, @Param("now") Instant now, Pageable page);

  @Query(
      "SELECT t FROM ServiceAccountToken t WHERE t.revocationReason IN :reasons AND"
          + OWN_ORGANIZATION
          + " ORDER BY t.expiresAt ASC")
  List<ServiceAccountToken> findByRevocationReasonsForAdminList(
      @Param("organizationId") UUID organizationId,
      @Param("reasons") Collection<ExternalAccessTokenRevocationReason> reasons,
      Pageable page);

  @Query(
      "SELECT t FROM ServiceAccountToken t WHERE t.revokedAt IS NULL"
          + " AND t.expiresAt > :now AND t.expiresAt <= :horizon AND"
          + OWN_ORGANIZATION
          + " ORDER BY t.expiresAt ASC")
  List<ServiceAccountToken> findExpiringWithinForAdminList(
      @Param("organizationId") UUID organizationId,
      @Param("now") Instant now,
      @Param("horizon") Instant horizon,
      Pageable page);

  /** Run out on their own and not yet recorded as such. */
  @Query(
      "SELECT t.id FROM ServiceAccountToken t WHERE t.lapseRecordedAt IS NULL"
          + " AND t.expiresAt <= :now")
  List<UUID> findLapsedIds(@Param("now") Instant now);

  /**
   * Records the expiry of one token: true for exactly one call, whichever instance makes it, and
   * never for a token that ended otherwise. The caller writes the audit entry only then.
   *
   * @return 1 when this call recorded it
   */
  @Modifying
  @Query(
      value =
          "UPDATE service_account_tokens SET revoked_at = :now, revocation_reason = 'EXPIRED',"
              + " lapse_recorded_at = :now WHERE id = :id AND revoked_at IS NULL"
              + " AND lapse_recorded_at IS NULL AND expires_at <= :now",
      nativeQuery = true)
  int recordLapse(@Param("id") UUID id, @Param("now") Instant now);

  /** Live tokens whose expiry falls into the half-open day window of a reminder. */
  @Query(
      "SELECT t FROM ServiceAccountToken t WHERE t.revokedAt IS NULL"
          + " AND t.expiresAt >= :from AND t.expiresAt < :to")
  List<ServiceAccountToken> findExpiringBetween(
      @Param("from") Instant from, @Param("to") Instant to);

  /**
   * Claims the reminder {@code leadDays} before the token's expiry, as {@code
   * ExternalAccessTokenRepository#claimExpiryReminder} does for a personal token.
   *
   * @return 1 when this call claimed it
   */
  @Transactional
  @Modifying
  @Query(
      value =
          "UPDATE service_account_tokens SET expiry_reminded_days = :leadDays WHERE id = :id"
              + " AND (expiry_reminded_days IS NULL OR expiry_reminded_days > :leadDays)",
      nativeQuery = true)
  int claimExpiryReminder(@Param("id") UUID id, @Param("leadDays") int leadDays);

  /** Removes dead rows whose retention, counted from the end of the token, has run out. */
  @Modifying
  @Query(
      "DELETE FROM ServiceAccountToken t WHERE t.lapseRecordedAt IS NOT NULL"
          + " AND t.lapseRecordedAt < :cutoff")
  int deleteLapsedBefore(@Param("cutoff") Instant cutoff);

  /**
   * Live tokens of active service accounts with a consent in force for this library - their share
   * of the library's {@code tokenCount}.
   */
  @Query(
      "SELECT COUNT(t) FROM ServiceAccountToken t, ServiceAccount s, ServiceAccountLibraryConsent c"
          + " WHERE s.id = t.serviceAccountId AND c.serviceAccountId = s.id"
          + " AND c.libraryId = :libraryId AND c.endedAt IS NULL AND c.expiresAt > :now"
          + " AND s.deactivatedAt IS NULL AND t.revokedAt IS NULL AND t.expiresAt > :now")
  long countActiveForConsentedLibrary(
      @Param("libraryId") UUID libraryId, @Param("now") Instant now);
}
