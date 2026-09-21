package io.opaa.group;

import io.opaa.api.types.SuccessionAddressee;
import io.opaa.api.types.SuccessionObjectType;
import io.opaa.permission.AssetGrant;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.permission.AssetOwnershipDirectory;
import io.opaa.permission.CapabilityGrantRepository;
import io.opaa.permission.GroupCapabilityService;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.GroupSpaceMembershipDirectory;
import io.opaa.permission.GroupSpaceMembershipRef;
import io.opaa.permission.GroupSubjectDirectory;
import io.opaa.permission.SuccessionFinding;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What every group of one organization holds and reaches, read in one pass - the basis of the two
 * group tabs of the operational list (#1819, ADR-0036 Entscheidung 6). Both ask the same three
 * questions and draw opposite conclusions, so they share this reader rather than each running its
 * own five queries.
 */
@Component
class GroupEffectReader {

  private final GroupRepository groups;
  private final AssetGrantRepository grants;
  private final CapabilityGrantRepository capabilities;
  private final GroupSpaceMembershipDirectory spaceMemberships;
  private final List<AssetOwnershipDirectory> ownershipDirectories;
  private final GroupSubjectDirectory groupDirectory;
  private final GroupCapabilityService groupCapability;
  private final GroupMembershipResolver membershipResolver;

  GroupEffectReader(
      GroupRepository groups,
      AssetGrantRepository grants,
      CapabilityGrantRepository capabilities,
      GroupSpaceMembershipDirectory spaceMemberships,
      List<AssetOwnershipDirectory> ownershipDirectories,
      GroupSubjectDirectory groupDirectory,
      GroupCapabilityService groupCapability,
      GroupMembershipResolver membershipResolver) {
    this.groups = groups;
    this.grants = grants;
    this.capabilities = capabilities;
    this.spaceMemberships = spaceMemberships;
    this.ownershipDirectories = ownershipDirectories;
    this.groupDirectory = groupDirectory;
    this.groupCapability = groupCapability;
    this.membershipResolver = membershipResolver;
  }

  /**
   * Effective groups that hold grants or are space members and have no active account left - their
   * releases reach nobody. Carries how many objects hang on the group, the figure the tab names.
   */
  List<SuccessionFinding> grantsWithoutRecipient(UUID organizationId) {
    Effects effects = effectsOf(organizationId);
    List<SuccessionFinding> findings = new ArrayList<>();
    for (Group group : effects.groups()) {
      Set<UUID> objects = effects.objectsByGroup().getOrDefault(group.getId(), Set.of());
      boolean reaches = membershipResolver.activeMemberCount(group.getId(), organizationId) > 0;
      boolean effective =
          groupDirectory.find(group.getId()).map(groupCapability::isEffective).orElse(false);
      if (!objects.isEmpty() && !reaches && effective) {
        findings.add(finding(group).withAffectedObjects(objects.size()));
      }
    }
    return findings;
  }

  /**
   * Internal groups without grant, without capability, without space membership, without ownership
   * and without an active member. Wildwuchs is made visible, not prevented - deleting stays an
   * action.
   */
  List<SuccessionFinding> groupsWithoutEffect(UUID organizationId) {
    Effects effects = effectsOf(organizationId);
    List<SuccessionFinding> findings = new ArrayList<>();
    for (Group group : effects.groups()) {
      if (!group.isInternal()) {
        continue;
      }
      boolean holdsSomething =
          !effects.objectsByGroup().getOrDefault(group.getId(), Set.of()).isEmpty()
              || effects.holdingCapabilities().contains(group.getId())
              || ownsAnything(group.getId());
      boolean reaches = membershipResolver.activeMemberCount(group.getId(), organizationId) > 0;
      if (!holdsSomething && !reaches) {
        findings.add(finding(group));
      }
    }
    return findings;
  }

  private Effects effectsOf(UUID organizationId) {
    List<Group> all = groups.findByOrganizationId(organizationId);
    if (all.isEmpty()) {
      return new Effects(List.of(), Map.of(), Set.of());
    }
    List<UUID> groupIds = all.stream().map(Group::getId).toList();
    Map<UUID, Set<UUID>> objectsByGroup = new HashMap<>();
    for (AssetGrant grant : grants.findBySubjectGroupIdIn(groupIds)) {
      objectsByGroup
          .computeIfAbsent(grant.getSubjectGroupId(), key -> new HashSet<>())
          .add(grant.getAssetId());
    }
    for (GroupSpaceMembershipRef membership : spaceMemberships.spaceMembershipsOf(groupIds)) {
      objectsByGroup
          .computeIfAbsent(membership.groupId(), key -> new HashSet<>())
          .add(membership.spaceId());
    }
    return new Effects(
        all, objectsByGroup, Set.copyOf(capabilities.findSubjectGroupIdsIn(groupIds)));
  }

  private boolean ownsAnything(UUID groupId) {
    for (AssetOwnershipDirectory directory : ownershipDirectories) {
      if (directory.existsAssetOwnedByGroup(groupId)) {
        return true;
      }
    }
    return false;
  }

  /** A protected group is named by its protection alone (ADR-0036, Entscheidung 9). */
  private static SuccessionFinding finding(Group group) {
    return SuccessionFinding.of(
        SuccessionObjectType.GROUP,
        group.getId(),
        group.isProtectedGroup() ? "Geschützte Gruppe" : group.getName(),
        SuccessionAddressee.SYSTEM_ADMINISTRATION);
  }

  /** One organization's groups with the objects they reach and the capabilities they hold. */
  private record Effects(
      List<Group> groups, Map<UUID, Set<UUID>> objectsByGroup, Set<UUID> holdingCapabilities) {}
}
