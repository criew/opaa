package io.opaa.space;

import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.SpaceVisibility;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers {@link SpaceVisibility} with {@link SpaceAccessPolicy#effectiveRole}: a space is visible
 * to whoever reaches it as owner, directly or through a group, the rule every space path applies.
 * The person's groups are resolved once for all spaces of a call.
 */
@Component
class SpaceVisibilityAdapter implements SpaceVisibility {

  private final SpaceRepository spaceRepository;
  private final GroupMembershipResolver groupMemberships;

  SpaceVisibilityAdapter(
      SpaceRepository spaceRepository, GroupMembershipResolver groupMemberships) {
    this.spaceRepository = spaceRepository;
    this.groupMemberships = groupMemberships;
  }

  @Override
  @Transactional(readOnly = true)
  public Set<UUID> visibleAmong(Collection<UUID> spaceIds, UUID userId) {
    Set<UUID> visible = new HashSet<>();
    if (spaceIds.isEmpty()) {
      return visible;
    }
    Set<UUID> groupIds = groupMemberships.groupIdsForUser(userId);
    for (Space space : spaceRepository.findAllById(spaceIds)) {
      if (SpaceAccessPolicy.effectiveRole(space, userId, groupIds) != null) {
        visible.add(space.getId());
      }
    }
    return visible;
  }
}
