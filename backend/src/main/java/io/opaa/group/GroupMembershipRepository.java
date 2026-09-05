package io.opaa.group;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupMembershipRepository extends JpaRepository<GroupMembership, UUID> {

  List<GroupMembership> findByGroupId(UUID groupId);

  Optional<GroupMembership> findByGroupIdAndUserId(UUID groupId, UUID userId);

  @Query("select m.group.id from GroupMembership m where m.userId = :userId")
  Set<UUID> findGroupIdsByUserId(@Param("userId") UUID userId);

  /**
   * The members of a group, scoped to the given organization - so a subject carrying the wrong
   * {@code organizationId} (whether by bug or by a crafted request) resolves to nobody instead of
   * leaking members across the organization boundary. Used by {@link
   * GroupMembershipResolver#resolveUserIds}. There is no unscoped equivalent on purpose: a caller
   * that skips the organization would reintroduce exactly the cross-tenant leak #199 closed.
   */
  @Query(
      "select m.userId from GroupMembership m "
          + "where m.group.id = :groupId and m.organizationId = :organizationId")
  Set<UUID> findUserIdsByGroupIdAndOrganizationId(
      @Param("groupId") UUID groupId, @Param("organizationId") UUID organizationId);
}
