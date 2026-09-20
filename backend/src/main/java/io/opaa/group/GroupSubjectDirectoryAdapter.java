package io.opaa.group;

import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.permission.GroupSubject;
import io.opaa.permission.GroupSubjectDirectory;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Answers {@link GroupSubjectDirectory} from {@link GroupRepository} - the one place a grant path
 * outside this package learns anything about a group, so {@code io.opaa.library} no longer holds a
 * {@link Group} or its repository (ADR-0036, Entscheidung 12).
 */
@Component
class GroupSubjectDirectoryAdapter implements GroupSubjectDirectory {

  private final GroupRepository groupRepository;
  private final OidcProviderRepository providerRepository;

  GroupSubjectDirectoryAdapter(
      GroupRepository groupRepository, OidcProviderRepository providerRepository) {
    this.groupRepository = groupRepository;
    this.providerRepository = providerRepository;
  }

  @Override
  public Optional<GroupSubject> find(UUID groupId) {
    return groupRepository
        .findById(groupId)
        .map(
            group ->
                new GroupSubject(
                    group.getId(),
                    group.getOrganizationId(),
                    group.getName(),
                    group.isDissolved(),
                    providerDisabled(group)));
  }

  /** An internal group has no provider and is therefore never held back by one. */
  private boolean providerDisabled(Group group) {
    if (group.getProviderId() == null) {
      return false;
    }
    return providerRepository
        .findById(group.getProviderId())
        .map(provider -> !provider.isEnabled())
        .orElse(false);
  }

  @Override
  public Map<UUID, String> namesById(Collection<UUID> groupIds) {
    Map<UUID, String> names = new HashMap<>();
    for (Group group : groupRepository.findAllById(groupIds)) {
      names.put(group.getId(), group.getName());
    }
    return names;
  }
}
