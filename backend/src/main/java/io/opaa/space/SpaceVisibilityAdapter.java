package io.opaa.space;

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
 */
@Component
class SpaceVisibilityAdapter implements SpaceVisibility {

  private final SpaceRepository spaceRepository;
  private final SpaceAccessPolicy accessPolicy;

  SpaceVisibilityAdapter(SpaceRepository spaceRepository, SpaceAccessPolicy accessPolicy) {
    this.spaceRepository = spaceRepository;
    this.accessPolicy = accessPolicy;
  }

  @Override
  @Transactional(readOnly = true)
  public Set<UUID> visibleAmong(Collection<UUID> spaceIds, UUID userId) {
    Set<UUID> visible = new HashSet<>();
    for (Space space : spaceRepository.findAllById(spaceIds)) {
      if (accessPolicy.effectiveRole(space, userId) != null) {
        visible.add(space.getId());
      }
    }
    return visible;
  }
}
