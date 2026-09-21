package io.opaa.group;

import io.opaa.auth.CurrentUser;
import io.opaa.permission.AssetGrant;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.permission.AssetOwnershipDirectory;
import io.opaa.permission.CapabilityGrantRepository;
import io.opaa.permission.GroupSpaceMembershipDirectory;
import io.opaa.permission.GroupSpaceMembershipRef;
import io.opaa.permission.PermissionSubject;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers "wo wirkt diese Gruppe" for a whole list of groups (ADR-0036, Entscheidung 2) - the
 * per-group counterpart of the aggregate figures a refused provider deletion quotes.
 *
 * <p>Counts, never rows: reading how many members a group has is a membership lookup and an audit
 * event, reading how many grants it holds is neither. The system administration therefore sees the
 * size of the work ahead without the interface fetching a single membership list on the way.
 */
@Service
public class GroupEffectsService {

  private final GroupRepository groupRepository;
  private final AssetGrantRepository grantRepository;
  private final CapabilityGrantRepository capabilityGrantRepository;
  private final List<AssetOwnershipDirectory> assetOwnershipDirectories;
  private final GroupSpaceMembershipDirectory spaceMembershipDirectory;
  private final GroupScopeUsageDirectory scopeUsageDirectory;

  public GroupEffectsService(
      GroupRepository groupRepository,
      AssetGrantRepository grantRepository,
      CapabilityGrantRepository capabilityGrantRepository,
      List<AssetOwnershipDirectory> assetOwnershipDirectories,
      GroupSpaceMembershipDirectory spaceMembershipDirectory,
      GroupScopeUsageDirectory scopeUsageDirectory) {
    this.groupRepository = groupRepository;
    this.grantRepository = grantRepository;
    this.capabilityGrantRepository = capabilityGrantRepository;
    this.assetOwnershipDirectories = assetOwnershipDirectories;
    this.spaceMembershipDirectory = spaceMembershipDirectory;
    this.scopeUsageDirectory = scopeUsageDirectory;
  }

  /**
   * Every group of the caller's organization with its effects, or only those of one provider - the
   * work list a refused provider deletion points to. Groups without any effect are part of the
   * answer: that they have none is the very information the work list needs.
   */
  @Transactional(readOnly = true)
  public List<GroupEffectsView> listEffects(CurrentUser caller, UUID providerId) {
    List<Group> groups =
        providerId == null
            ? groupRepository.findByOrganizationId(caller.organizationId())
            : groupRepository.findByProviderId(providerId).stream()
                .filter(group -> group.getOrganizationId().equals(caller.organizationId()))
                .toList();
    if (groups.isEmpty()) {
      return List.of();
    }
    List<UUID> groupIds = groups.stream().map(Group::getId).toList();

    Map<UUID, Long> grantsByGroup = new HashMap<>();
    Map<UUID, Set<UUID>> assetsByGroup = new HashMap<>();
    for (AssetGrant grant : grantRepository.findBySubjectGroupIdIn(groupIds)) {
      grantsByGroup.merge(grant.getSubjectGroupId(), 1L, Long::sum);
      assetsByGroup
          .computeIfAbsent(grant.getSubjectGroupId(), key -> new HashSet<>())
          .add(grant.getAssetId());
    }

    Map<UUID, Long> membershipsByGroup = new HashMap<>();
    Map<UUID, Set<UUID>> spacesByGroup = new HashMap<>();
    for (GroupSpaceMembershipRef membership :
        spaceMembershipDirectory.spaceMembershipsOf(groupIds)) {
      membershipsByGroup.merge(membership.groupId(), 1L, Long::sum);
      spacesByGroup
          .computeIfAbsent(membership.groupId(), key -> new HashSet<>())
          .add(membership.spaceId());
    }

    Map<UUID, Long> authorizationsByGroup = new HashMap<>();
    for (UUID groupId : scopeUsageDirectory.scopeGroupsOfUnspentAuthorizations(groupIds)) {
      authorizationsByGroup.merge(groupId, 1L, Long::sum);
    }

    return groups.stream()
        .map(
            group ->
                new GroupEffectsView(
                    group,
                    grantsByGroup.getOrDefault(group.getId(), 0L),
                    assetsByGroup.getOrDefault(group.getId(), Set.of()).size(),
                    membershipsByGroup.getOrDefault(group.getId(), 0L),
                    spacesByGroup.getOrDefault(group.getId(), Set.of()).size(),
                    ownedAssetsOf(group),
                    capabilityGrantRepository.countBySubjectGroupId(group.getId()),
                    authorizationsByGroup.getOrDefault(group.getId(), 0L)))
        .sorted(Comparator.comparing(view -> view.group().getName(), String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  private long ownedAssetsOf(Group group) {
    PermissionSubject owner = PermissionSubject.group(group.getId(), group.getOrganizationId());
    long owned = 0;
    for (AssetOwnershipDirectory directory : assetOwnershipDirectories) {
      owned += directory.countAssetsOwnedBy(owner);
    }
    return owned;
  }
}
