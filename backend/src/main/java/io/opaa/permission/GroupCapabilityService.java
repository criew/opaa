package io.opaa.permission;

import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * "Handlungsfähige Gruppe" as ADR-0036 defines it, in one place: an effective group - not
 * dissolved, its provider switched on, still maintained - with at least one <b>active</b> account.
 * The measure of whether a group can act as an owner or as a space {@code ADMIN}, and the
 * difference to "wirksam", which may be empty.
 *
 * <p>Counting active accounts rather than membership rows is the point: a group of twenty whose
 * eighteen are locked can act through two people, and one whose members are all locked can act
 * through nobody.
 */
@Service
public class GroupCapabilityService {

  private final GroupSubjectDirectory groupDirectory;
  private final GroupMembershipResolver membershipResolver;

  GroupCapabilityService(
      GroupSubjectDirectory groupDirectory, GroupMembershipResolver membershipResolver) {
    this.groupDirectory = groupDirectory;
    this.membershipResolver = membershipResolver;
  }

  /** Whether the group may act - unknown, dissolved, switched off or empty of active accounts. */
  public boolean isCapable(UUID groupId) {
    GroupSubject group = groupDirectory.find(groupId).orElse(null);
    return group != null && isCapable(group);
  }

  /** The same question for a group already resolved - saves the second lookup on a list path. */
  public boolean isCapable(GroupSubject group) {
    if (group.dissolved() || group.providerDisabled() || group.unmaintained()) {
      return false;
    }
    return membershipResolver.activeMemberCount(group.id(), group.organizationId()) > 0;
  }

  /** Whether the group is effective - it may hold and receive rights, and it may be empty. */
  public boolean isEffective(GroupSubject group) {
    return !group.dissolved() && !group.providerDisabled() && !group.unmaintained();
  }
}
