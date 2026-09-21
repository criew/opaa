package io.opaa.space;

import io.opaa.permission.GroupSpaceMembershipRef;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpaceMembershipRepository extends JpaRepository<SpaceMembership, UUID> {

  List<SpaceMembership> findBySpaceId(UUID spaceId);

  /**
   * Whether the person holds a membership of their own. {@code user_id} is only ever set on a
   * {@code USER} row (chk_space_memberships_subject), so no further predicate on {@code
   * subject_type} is needed here or below.
   */
  boolean existsBySpaceIdAndUserId(UUID spaceId, UUID userId);

  /** Whether one of the person's groups holds a membership. Never called with an empty set. */
  boolean existsBySpaceIdAndGroupIdIn(UUID spaceId, Collection<UUID> groupIds);

  /** How many spaces the account is a member of - part of what a handover moves (#1563). */
  long countByUserId(UUID userId);

  /**
   * The space memberships the given groups hold - the one query behind {@link
   * io.opaa.permission.GroupSpaceMembershipDirectory}. Never called with an empty set.
   */
  @Query(
      "select new io.opaa.permission.GroupSpaceMembershipRef(m.groupId, m.space.id) "
          + "from SpaceMembership m where m.groupId in :groupIds")
  List<GroupSpaceMembershipRef> findSpaceMembershipsOfGroups(
      @Param("groupIds") Collection<UUID> groupIds);
}
