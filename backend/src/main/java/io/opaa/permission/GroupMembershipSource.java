package io.opaa.permission;

import java.util.Set;
import java.util.UUID;

/**
 * The two membership questions {@link GroupMembershipResolver} asks of whoever stores group
 * memberships - the port that keeps this package free of a dependency on {@code io.opaa.group}
 * (ADR-0036, Entscheidung 12). Implemented by {@code io.opaa.group.GroupMembershipRepository}: the
 * permission model asks, the group administration answers.
 */
public interface GroupMembershipSource {

  /** The ids of the groups {@code userId} is a direct member of. */
  Set<UUID> findGroupIdsByUserId(UUID userId);

  /**
   * The members of a group, scoped to the given organization - so a subject carrying the wrong
   * {@code organizationId} (whether by bug or by a crafted request) resolves to nobody instead of
   * leaking members across the organization boundary. There is no unscoped equivalent on purpose: a
   * caller that skips the organization would reintroduce exactly the cross-tenant leak #199 closed.
   */
  Set<UUID> findUserIdsByGroupIdAndOrganizationId(UUID groupId, UUID organizationId);

  /**
   * How many members that same scope holds, without loading them. Separate from the method above
   * because {@link GroupMembershipResolver#activeMemberCount} is on the list path of every space
   * ({@code SpaceService#listSpaces}), where loading a whole department to learn its size is the
   * difference between one indexed count and a row set per space.
   */
  long countUserIdsByGroupIdAndOrganizationId(UUID groupId, UUID organizationId);
}
