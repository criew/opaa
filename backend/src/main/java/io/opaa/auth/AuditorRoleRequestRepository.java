package io.opaa.auth;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@link AuditorRoleRequest}. "Open" means not declined; a declined request exists
 * only for a provider and only to keep its claim from reopening it.
 */
public interface AuditorRoleRequestRepository extends JpaRepository<AuditorRoleRequest, UUID> {

  @Query("SELECT r FROM AuditorRoleRequest r WHERE r.userId = :userId AND r.declinedAt IS NULL")
  Optional<AuditorRoleRequest> findOpenByUserId(@Param("userId") UUID userId);

  @Query(
      "SELECT r FROM AuditorRoleRequest r WHERE r.organizationId = :organizationId"
          + " AND r.declinedAt IS NULL ORDER BY r.requestedAt ASC")
  List<AuditorRoleRequest> findOpenByOrganizationId(@Param("organizationId") UUID organizationId);

  @Query(
      "SELECT r.userId FROM AuditorRoleRequest r WHERE r.userId IN :userIds"
          + " AND r.declinedAt IS NULL")
  List<UUID> findOpenUserIdsIn(@Param("userIds") Collection<UUID> userIds);

  boolean existsByUserId(UUID userId);

  /**
   * Opens the request unless the account already has one, open or declined; the count says whether
   * this call opened it. Of two concurrent calls exactly one gets 1, neither an error.
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          "INSERT INTO auditor_role_requests (id, organization_id, user_id, requested_by_user_id,"
              + " requested_by_provider_id, requested_at) VALUES (:id, :organizationId, :userId,"
              + " :requestedByUserId, :requestedByProviderId, :requestedAt)"
              + " ON CONFLICT (user_id) DO NOTHING",
      nativeQuery = true)
  int insertIfAbsent(
      @Param("id") UUID id,
      @Param("organizationId") UUID organizationId,
      @Param("userId") UUID userId,
      @Param("requestedByUserId") UUID requestedByUserId,
      @Param("requestedByProviderId") UUID requestedByProviderId,
      @Param("requestedAt") Instant requestedAt);

  /** Removes the account's request, open or declined; the count says whether one was there. */
  @Modifying(flushAutomatically = true)
  @Query("DELETE FROM AuditorRoleRequest r WHERE r.userId = :userId")
  int deleteAllOfUser(@Param("userId") UUID userId);

  /** Marks an open request of a provider as declined; the count says whether it was still open. */
  @Modifying(flushAutomatically = true)
  @Query(
      "UPDATE AuditorRoleRequest r SET r.declinedAt = :at WHERE r.id = :id"
          + " AND r.declinedAt IS NULL AND r.requestedByProviderId IS NOT NULL")
  int markDeclined(@Param("id") UUID id, @Param("at") Instant at);

  /**
   * The open request under a row lock, so of two concurrent decisions exactly one finds it; the
   * other finds nothing once the first committed.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT r FROM AuditorRoleRequest r WHERE r.id = :id AND r.organizationId = :organizationId"
          + " AND r.declinedAt IS NULL")
  Optional<AuditorRoleRequest> findForDecision(
      @Param("id") UUID id, @Param("organizationId") UUID organizationId);
}
