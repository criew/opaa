package io.opaa.group;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.UserRepository;
import io.opaa.permission.DisclosedGroupMember;
import io.opaa.permission.GroupMemberDisclosure;
import io.opaa.permission.GroupMemberDisclosureDirectory;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.GroupSizeProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Answers {@link GroupMemberDisclosureDirectory} from this package - the one place a grant giver
 * outside {@code io.opaa.group} learns who is in a group, so neither {@code io.opaa.library} nor
 * {@code io.opaa.space} holds a {@link Group} or its repository (ADR-0036, Entscheidung 12). The
 * rule every decision below follows, and the reason for the audit event, are in the port's Javadoc.
 */
@Component
class GroupMemberDisclosureAdapter implements GroupMemberDisclosureDirectory {

  private final GroupRepository groupRepository;
  private final GroupStewardRepository stewardRepository;
  private final GroupContactRepository contactRepository;
  private final GroupMembershipResolver membershipResolver;
  private final GroupSizeProperties groupSizeProperties;
  private final UserRepository userRepository;
  private final AuditEventRecorder auditEventRecorder;

  GroupMemberDisclosureAdapter(
      GroupRepository groupRepository,
      GroupStewardRepository stewardRepository,
      GroupContactRepository contactRepository,
      GroupMembershipResolver membershipResolver,
      GroupSizeProperties groupSizeProperties,
      UserRepository userRepository,
      AuditEventRecorder auditEventRecorder) {
    this.groupRepository = groupRepository;
    this.stewardRepository = stewardRepository;
    this.contactRepository = contactRepository;
    this.membershipResolver = membershipResolver;
    this.groupSizeProperties = groupSizeProperties;
    this.userRepository = userRepository;
    this.auditEventRecorder = auditEventRecorder;
  }

  @Override
  public Optional<GroupMemberDisclosure> disclose(
      UUID groupId, UUID organizationId, CurrentUser caller, int offset, int limit) {
    Group group = groupRepository.findById(groupId).orElse(null);
    if (group == null || !group.getOrganizationId().equals(organizationId)) {
      return Optional.empty();
    }
    // Limits (b) and (c) of the port's rule.
    if (!group.isSelectableAsSubject()) {
      return Optional.empty();
    }
    int active = membershipResolver.activeMemberCount(group.getId(), organizationId);
    // Recorded before anything is withheld: what is on the record is that this caller asked about
    // this group, and how many accounts it reached at that moment - never the names.
    recordSystemAdminRetrieval(group, caller, active);
    // Limit (d): whom to ask, instead of name, size and members.
    if (group.isProtectedGroup()) {
      return Optional.of(
          new GroupMemberDisclosure(
              group.getId(), null, true, false, null, List.of(), responsibleNamesOf(group)));
    }
    // Limit (e): below the Mindestgruppengröße the list is the figure the growth signal withholds.
    if (active < groupSizeProperties.minimumGroupSize()) {
      return Optional.of(
          new GroupMemberDisclosure(
              group.getId(), group.getName(), false, true, null, List.of(), List.of()));
    }
    int page = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
    List<UUID> memberIds =
        membershipResolver.activeMemberIdsPage(
            group.getId(), organizationId, page, Math.max(offset, 0));
    return Optional.of(
        new GroupMemberDisclosure(
            group.getId(), group.getName(), false, false, active, toMembers(memberIds), List.of()));
  }

  /**
   * The same event and the same condition as {@code GroupService#listMembers}: a system
   * administrator who stewards none of the group leaves a record, whether they reached the list
   * through the group administration or through an object.
   */
  private void recordSystemAdminRetrieval(Group group, CurrentUser caller, int activeMembers) {
    if (!caller.isSystemAdmin()
        || stewardRepository.existsByGroupIdAndUserId(group.getId(), caller.id())) {
      return;
    }
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(group.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.GROUP_MEMBERS_READ)
            .object(AuditObjectType.GROUP, group.getId(), group.getName())
            .after(Map.of("memberCount", activeMembers))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  /** Keeps the order of the query - it is what makes paging stable. */
  private List<DisclosedGroupMember> toMembers(List<UUID> memberIds) {
    Map<UUID, String> displayNames = userRepository.displayNamesById(memberIds);
    List<DisclosedGroupMember> members = new ArrayList<>(memberIds.size());
    for (UUID memberId : memberIds) {
      members.add(new DisclosedGroupMember(memberId, displayNames.get(memberId)));
    }
    return members;
  }

  /**
   * Whom a grant giver may ask about a protected group: the stewards of an internal group, the
   * contact points of a provider group (#1875). Resolved for a protected group alone, so it costs
   * no per-row lookup anywhere else.
   */
  private List<String> responsibleNamesOf(Group group) {
    List<UUID> userIds =
        group.isInternal()
            ? stewardRepository.findByGroupIdOrderByCreatedAtAsc(group.getId()).stream()
                .map(GroupSteward::getUserId)
                .toList()
            : contactRepository.findByGroupIdOrderByCreatedAtAsc(group.getId()).stream()
                .map(GroupContact::getUserId)
                .toList();
    Map<UUID, String> displayNames = userRepository.displayNamesById(userIds);
    return userIds.stream().map(displayNames::get).filter(Objects::nonNull).toList();
  }
}
