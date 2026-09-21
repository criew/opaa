package io.opaa.space;

import io.opaa.api.types.SpaceRole;
import io.opaa.permission.AssetType;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.GroupSpaceMembershipDirectory;
import io.opaa.permission.GroupSpaceMembershipRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Answers {@link GroupSpaceMembershipDirectory} from this package's repository - the one place the
 * group administration, the provider administration and the transfer operation learn that a group
 * is a space member, without any of them depending on {@code io.opaa.space} (ADR-0036, Entscheidung
 * 12).
 */
@Component
class GroupSpaceMembershipDirectoryAdapter implements GroupSpaceMembershipDirectory {

  private final SpaceMembershipRepository membershipRepository;
  private final SpaceRepository spaceRepository;
  private final SpaceMembershipHistoryService membershipHistory;
  private final GroupMembershipResolver groupMemberships;

  GroupSpaceMembershipDirectoryAdapter(
      SpaceMembershipRepository membershipRepository,
      SpaceRepository spaceRepository,
      SpaceMembershipHistoryService membershipHistory,
      GroupMembershipResolver groupMemberships) {
    this.membershipRepository = membershipRepository;
    this.spaceRepository = spaceRepository;
    this.membershipHistory = membershipHistory;
    this.groupMemberships = groupMemberships;
  }

  @Override
  public AssetType spaceAssetType() {
    return Space.ASSET_TYPE;
  }

  @Override
  public List<GroupSpaceMembershipRef> spaceMembershipsOf(Collection<UUID> groupIds) {
    if (groupIds.isEmpty()) {
      return List.of();
    }
    return membershipRepository.findSpaceMembershipsOfGroups(groupIds);
  }

  /**
   * Where the target group is already a member, the stronger of the two roles stays: a transfer
   * hands rights over, it never lowers what the target already had. The target's own interval is
   * only rewritten when its role actually rises - an unchanged state is not a new state.
   *
   * <p>{@code memberCountAtGrant} of a newly written membership is today's figure, not the
   * source's: the number exists to be compared with the one of tomorrow (ADR-0036, Entscheidung 9),
   * and carrying the source group's old count over would compare two different groups.
   */
  @Override
  public List<UUID> transferSpaceMemberships(
      UUID sourceGroupId, UUID targetGroupId, UUID actorUserId, UUID transferId, Instant at) {
    List<GroupSpaceMembershipRef> held = spaceMembershipsOf(List.of(sourceGroupId));
    List<UUID> touched = new ArrayList<>();
    for (GroupSpaceMembershipRef ref : held) {
      Space space = spaceRepository.findByIdWithMemberships(ref.spaceId()).orElseThrow();
      SpaceMembership source = groupMembership(space, sourceGroupId);
      SpaceMembership existing = groupMembership(space, targetGroupId);
      membershipHistory.recordTransferredOut(source, actorUserId, transferId, at);
      if (existing == null) {
        SpaceMembership moved =
            SpaceMembership.ofGroup(
                targetGroupId,
                source.getRole(),
                groupMemberships.activeMemberCount(targetGroupId, space.getOrganizationId()),
                space.getOrganizationId());
        space.addMembership(moved);
        membershipHistory.recordTransferredIn(moved, actorUserId, transferId, at);
      } else if (source.getRole().ordinal() > existing.getRole().ordinal()) {
        existing.setRole(stronger(existing.getRole(), source.getRole()));
        membershipHistory.recordTransferredIn(existing, actorUserId, transferId, at);
      }
      space.removeMembership(source);
      spaceRepository.save(space);
      touched.add(space.getId());
    }
    return touched;
  }

  private static SpaceRole stronger(SpaceRole one, SpaceRole other) {
    return one.ordinal() >= other.ordinal() ? one : other;
  }

  private static SpaceMembership groupMembership(Space space, UUID groupId) {
    return space.getMemberships().stream()
        .filter(membership -> membership.isGroupSubject())
        .filter(membership -> groupId.equals(membership.getGroupId()))
        .findFirst()
        .orElse(null);
  }
}
