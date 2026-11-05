package io.opaa.externalaccess.token;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ServiceAccountLibraryConsentRepository
    extends JpaRepository<ServiceAccountLibraryConsent, UUID> {

  /** The open row of one pair, in force or run out; there is at most one. */
  Optional<ServiceAccountLibraryConsent> findByServiceAccountIdAndLibraryIdAndEndedAtIsNull(
      UUID serviceAccountId, UUID libraryId);

  List<ServiceAccountLibraryConsent> findByLibraryIdAndEndedAtIsNull(UUID libraryId);

  List<ServiceAccountLibraryConsent> findByServiceAccountIdAndEndedAtIsNull(UUID serviceAccountId);

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
          + " AND c.endedAt IS NULL AND c.expiresAt > :now AND c.expiresAt <= :until")
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
}
