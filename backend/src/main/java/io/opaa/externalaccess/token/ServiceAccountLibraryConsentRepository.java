package io.opaa.externalaccess.token;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ServiceAccountLibraryConsentRepository
    extends JpaRepository<ServiceAccountLibraryConsent, UUID> {

  /**
   * The open row of one pair, in force or run out; there is at most one. Locked until the caller's
   * transaction ends, so no write lands between its read and the caller's write. A change and a
   * withdrawal also lock the account's row first, so the later one reads what the earlier
   * committed, a replacing row included.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<ServiceAccountLibraryConsent> findByServiceAccountIdAndLibraryIdAndEndedAtIsNull(
      UUID serviceAccountId, UUID libraryId);

  List<ServiceAccountLibraryConsent> findByLibraryIdAndEndedAtIsNull(UUID libraryId);

  List<ServiceAccountLibraryConsent> findByServiceAccountIdAndEndedAtIsNull(UUID serviceAccountId);

  List<ServiceAccountLibraryConsent> findByServiceAccountIdInAndEndedAtIsNull(
      List<UUID> serviceAccountIds);

  /** The libraries open consents name - some may have been deleted since. */
  @Query("SELECT DISTINCT c.libraryId FROM ServiceAccountLibraryConsent c WHERE c.endedAt IS NULL")
  List<UUID> findOpenLibraryIds();

  /** Open consents that ran out at or before {@code now} and whose expiry is not on record yet. */
  @Query(
      "SELECT c FROM ServiceAccountLibraryConsent c"
          + " WHERE c.endedAt IS NULL AND c.expiresAt <= :now AND c.expiryRecordedAt IS NULL")
  List<ServiceAccountLibraryConsent> findRunOutUnrecorded(@Param("now") Instant now);

  /** The libraries consented to a service account at {@code now} - the core of its view. */
  @Query(
      "SELECT c.libraryId FROM ServiceAccountLibraryConsent c"
          + " WHERE c.serviceAccountId = :serviceAccountId AND c.endedAt IS NULL"
          + " AND c.expiresAt > :now")
  Set<UUID> findLibraryIdsInForce(
      @Param("serviceAccountId") UUID serviceAccountId, @Param("now") Instant now);

  /**
   * Consents in force of active service accounts that run out within the lead and have not been
   * announced yet.
   */
  @Query(
      "SELECT c FROM ServiceAccountLibraryConsent c, ServiceAccount s"
          + " WHERE s.id = c.serviceAccountId AND s.deactivatedAt IS NULL"
          + " AND c.endedAt IS NULL AND c.reminderSentAt IS NULL"
          + " AND c.expiresAt > :now AND c.expiresAt <= :until")
  List<ServiceAccountLibraryConsent> findExpiringBetween(
      @Param("now") Instant now, @Param("until") Instant until);

  /**
   * Claims the reminder of one consent row: true for exactly one call, whichever instance makes it.
   * A moved end is a new row and earns a fresh reminder.
   *
   * @return 1 when this call claimed it
   */
  @Modifying
  @Query(
      value =
          "UPDATE service_account_library_consents SET reminder_sent_at = :now"
              + " WHERE id = :id AND reminder_sent_at IS NULL",
      nativeQuery = true)
  int claimReminder(@Param("id") UUID id, @Param("now") Instant now);

  /**
   * Claims the expiry entry of one consent row: true for exactly one call, whichever instance makes
   * it, and only while the row is still open.
   *
   * @return 1 when this call claimed it
   */
  @Modifying
  @Query(
      value =
          "UPDATE service_account_library_consents SET expiry_recorded_at = :now"
              + " WHERE id = :id AND expiry_recorded_at IS NULL AND ended_at IS NULL",
      nativeQuery = true)
  int claimExpiry(@Param("id") UUID id, @Param("now") Instant now);

  /**
   * Ends the open consents of a library that no longer exists. The rows stay as its history.
   *
   * @return how many rows were ended
   */
  @Modifying
  @Query(
      value =
          "UPDATE service_account_library_consents SET ended_at = :now,"
              + " end_reason = 'LIBRARY_DELETED' WHERE library_id = :libraryId AND ended_at IS NULL",
      nativeQuery = true)
  int endForDeletedLibrary(@Param("libraryId") UUID libraryId, @Param("now") Instant now);
}
