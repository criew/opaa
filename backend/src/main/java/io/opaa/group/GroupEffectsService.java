package io.opaa.group;

import io.opaa.auth.CurrentUser;
import io.opaa.permission.AssetGrant;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.permission.AssetOwnershipDirectory;
import io.opaa.permission.CapabilityGrantRepository;
import io.opaa.permission.GroupSpaceMembershipDirectory;
import io.opaa.permission.GroupSpaceMembershipRef;
import java.util.Collection;
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
   * The effects of the named groups, of one provider's groups, or - with neither narrowing given -
   * of every group of the caller's organization. Groups without any effect are part of the answer:
   * that they have none is the very information the work list needs.
   *
   * <p><b>A fixed number of queries whatever the list's length.</b> Every one of the five effect
   * kinds is read for the whole set at once; a count per group would make an organization with a
   * few hundred directory units a thousand-query page (#1821).
   */
  @Transactional(readOnly = true)
  public List<GroupEffectsView> listEffects(
      CurrentUser caller, UUID providerId, Collection<UUID> groupIdFilter) {
    List<Group> groups = load(caller, providerId, groupIdFilter);
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

    Map<UUID, Long> capabilitiesByGroup = new HashMap<>();
    for (CapabilityGrantRepository.SubjectGroupCapabilityCount count :
        capabilityGrantRepository.countBySubjectGroupIdIn(groupIds)) {
      capabilitiesByGroup.put(count.getSubjectGroupId(), count.getCapabilityCount());
    }

    Map<UUID, Long> ownedByGroup = ownedAssetsOf(groupIds, caller.organizationId());

    return groups.stream()
        .map(
            group ->
                new GroupEffectsView(
                    group,
                    grantsByGroup.getOrDefault(group.getId(), 0L),
                    assetsByGroup.getOrDefault(group.getId(), Set.of()).size(),
                    membershipsByGroup.getOrDefault(group.getId(), 0L),
                    spacesByGroup.getOrDefault(group.getId(), Set.of()).size(),
                    ownedByGroup.getOrDefault(group.getId(), 0L),
                    capabilitiesByGroup.getOrDefault(group.getId(), 0L),
                    authorizationsByGroup.getOrDefault(group.getId(), 0L)))
        .sorted(Comparator.comparing(view -> view.group().getName(), String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  private List<Group> load(CurrentUser caller, UUID providerId, Collection<UUID> groupIdFilter) {
    UUID organizationId = caller.organizationId();
    List<Group> groups;
    if (groupIdFilter != null && !groupIdFilter.isEmpty()) {
      groups = groupRepository.findAllById(groupIdFilter);
    } else if (providerId != null) {
      groups = groupRepository.findByProviderId(providerId);
    } else {
      groups = groupRepository.findByOrganizationId(organizationId);
    }
    return groups.stream()
        .filter(group -> group.getOrganizationId().equals(organizationId))
        .filter(group -> providerId == null || providerId.equals(group.getProviderId()))
        .toList();
  }

  /** One grouped query per asset type instead of one count per group and type. */
  private Map<UUID, Long> ownedAssetsOf(List<UUID> groupIds, UUID organizationId) {
    Map<UUID, Long> owned = new HashMap<>();
    for (AssetOwnershipDirectory directory : assetOwnershipDirectories) {
      directory
          .countAssetsOwnedByGroups(groupIds, organizationId)
          .forEach((groupId, count) -> owned.merge(groupId, count, Long::sum));
    }
    return owned;
  }
}
