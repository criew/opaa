package io.opaa.group;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupMembershipHistoryRepository
    extends JpaRepository<GroupMembershipHistory, UUID> {

  Optional<GroupMembershipHistory> findByGroupIdAndUserIdAndValidToIsNull(
      UUID groupId, UUID userId);

  /**
   * Every group {@code userId} belonged to at {@code asOf} - the interval's {@code validFrom <=
   * asOf} and ({@code validTo IS NULL OR validTo > asOf}). Used by {@link
   * io.opaa.library.PermissionHistoryService#readableLibraryIdsAsOf} to resolve the group side of
   * the readable-library formula at a past instant, the same way {@link
   * io.opaa.group.GroupMembershipResolver#groupIdsForUser} resolves it for "now".
   */
  @Query(
      "select h.groupId from GroupMembershipHistory h "
          + "where h.userId = :userId and h.organizationId = :organizationId "
          + "and h.validFrom <= :asOf and (h.validTo is null or h.validTo > :asOf)")
  Set<UUID> findGroupIdsByUserIdAsOf(
      @Param("userId") UUID userId,
      @Param("organizationId") UUID organizationId,
      @Param("asOf") Instant asOf);
}
