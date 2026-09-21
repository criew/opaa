package io.opaa.group;

import io.opaa.api.types.GroupKind;
import io.opaa.api.types.GroupMechanism;
import io.opaa.api.types.GroupOrigin;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.permission.GroupAttribution;
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
  private final GroupStewardRepository stewardRepository;
  private final GroupMembershipRepository membershipRepository;

  GroupSubjectDirectoryAdapter(
      GroupRepository groupRepository,
      OidcProviderRepository providerRepository,
      GroupStewardRepository stewardRepository,
      GroupMembershipRepository membershipRepository) {
    this.groupRepository = groupRepository;
    this.providerRepository = providerRepository;
    this.stewardRepository = stewardRepository;
    this.membershipRepository = membershipRepository;
  }

  @Override
  public Optional<GroupSubject> find(UUID groupId) {
    return groupRepository
        .findById(groupId)
        .map(
            group -> {
              OidcProvider provider =
                  group.getProviderId() == null
                      ? null
                      : providerRepository.findById(group.getProviderId()).orElse(null);
              return new GroupSubject(
                  group.getId(),
                  group.getOrganizationId(),
                  group.getName(),
                  group.isDissolved(),
                  // An internal group has no provider and is therefore never held back by one.
                  provider != null && !provider.isEnabled(),
                  unmaintained(group, provider),
                  group.isProtectedGroup(),
                  group.isInternal());
            });
  }

  /**
   * A token group whose provider has since switched to the directory run (#1816, ADR-0036
   * Entscheidung 3): the run reports it as no longer maintained and leaves its membership frozen,
   * so it may hold what it holds but must not become a new grant target.
   */
  private boolean unmaintained(Group group, OidcProvider provider) {
    return provider != null
        && group.getKind() == GroupKind.IDENTITY_PROVIDER
        && provider.isDirectorySyncEnabled();
  }

  @Override
  public boolean isSelectableBy(UUID groupId, UUID userId, boolean systemAdmin) {
    Group group = groupRepository.findById(groupId).orElse(null);
    if (group == null) {
      return false;
    }
    if (group.isSelectableAsSubject() || systemAdmin) {
      return true;
    }
    return stewardRepository.existsByGroupIdAndUserId(groupId, userId)
        || membershipRepository.findByGroupIdAndUserId(groupId, userId).isPresent();
  }

  @Override
  public Map<UUID, String> namesById(Collection<UUID> groupIds) {
    Map<UUID, String> names = new HashMap<>();
    for (Group group : groupRepository.findAllById(groupIds)) {
      names.put(group.getId(), group.getName());
    }
    return names;
  }

  @Override
  public Map<UUID, GroupAttribution> attributionsById(Collection<UUID> groupIds) {
    Map<UUID, GroupAttribution> attributions = new HashMap<>();
    Map<UUID, OidcProvider> providers = new HashMap<>();
    for (Group group : groupRepository.findAllById(groupIds)) {
      OidcProvider provider =
          group.getProviderId() == null
              ? null
              : providers.computeIfAbsent(
                  group.getProviderId(), id -> providerRepository.findById(id).orElse(null));
      attributions.put(
          group.getId(),
          new GroupAttribution(
              group.getId(),
              group.getName(),
              provider == null ? GroupOrigin.INTERNAL : GroupOrigin.PROVIDER,
              provider == null ? null : provider.getDisplayName(),
              provider == null ? GroupMechanism.NONE : provider.groupMechanism(),
              group.isProtectedGroup()));
    }
    return attributions;
  }
}
