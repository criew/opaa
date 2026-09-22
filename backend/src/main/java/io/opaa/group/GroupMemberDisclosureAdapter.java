package io.opaa.group;

import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.permission.DisclosedGroupMember;
import io.opaa.permission.GroupMemberDisclosure;
import io.opaa.permission.GroupMemberDisclosureDirectory;
import io.opaa.permission.GroupMembershipResolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Answers {@link GroupMemberDisclosureDirectory} from this package - the one place a grant giver
 * outside {@code io.opaa.group} learns who is in a group (#1880, ADR-0036 Entscheidung 9), so
 * neither {@code io.opaa.library} nor {@code io.opaa.space} holds a {@link Group} or its repository
 * (Entscheidung 12).
 *
 * <p>Deliberately without an audit event: ADR-0036, Entscheidung 9 records the retrieval for {@code
 * SYSTEM_ADMIN} alone ({@code GroupService#listMembers}, #1821). A grant giver reads the group they
 * themselves brought into their own object, and who did that and when is already on the grant.
 */
@Component
class GroupMemberDisclosureAdapter implements GroupMemberDisclosureDirectory {

  private final GroupRepository groupRepository;
  private final GroupStewardRepository stewardRepository;
  private final GroupMembershipResolver membershipResolver;
  private final UserRepository userRepository;

  GroupMemberDisclosureAdapter(
      GroupRepository groupRepository,
      GroupStewardRepository stewardRepository,
      GroupMembershipResolver membershipResolver,
      UserRepository userRepository) {
    this.groupRepository = groupRepository;
    this.stewardRepository = stewardRepository;
    this.membershipResolver = membershipResolver;
    this.userRepository = userRepository;
  }

  @Override
  public Optional<GroupMemberDisclosure> disclose(
      UUID groupId, UUID organizationId, int offset, int limit) {
    Group group = groupRepository.findById(groupId).orElse(null);
    if (group == null || !group.getOrganizationId().equals(organizationId)) {
      return Optional.empty();
    }
    // Limits (b) and (c) of ADR-0036, Entscheidung 9: an internal group is disclosed only once its
    // stewards released it for use, and the default is not released. A provider group needs no
    // release - its existence is not a decision of this house.
    if (!group.isSelectableAsSubject()) {
      return Optional.empty();
    }
    // Limit (d): a protected group answers with the people to ask, never with its members - and
    // not with its name or its size either, both of which are the disclosure here.
    if (group.isProtectedGroup()) {
      return Optional.of(
          new GroupMemberDisclosure(
              group.getId(), null, true, null, List.of(), responsibleNamesOf(group)));
    }
    int page = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
    List<UUID> memberIds =
        membershipResolver.activeMemberIdsPage(
            group.getId(), organizationId, page, Math.max(offset, 0));
    return Optional.of(
        new GroupMemberDisclosure(
            group.getId(),
            group.getName(),
            false,
            membershipResolver.activeMemberCount(group.getId(), organizationId),
            toMembers(memberIds),
            List.of()));
  }

  /**
   * Keeps the order the query produced - {@code findAllById} answers in no defined order, and the
   * order is what makes paging stable.
   */
  private List<DisclosedGroupMember> toMembers(List<UUID> memberIds) {
    Map<UUID, String> displayNames = new HashMap<>();
    for (User user : userRepository.findAllById(memberIds)) {
      displayNames.put(
          user.getId(), user.getDisplayName() != null ? user.getDisplayName() : user.getEmail());
    }
    List<DisclosedGroupMember> members = new ArrayList<>(memberIds.size());
    for (UUID memberId : memberIds) {
      members.add(new DisclosedGroupMember(memberId, displayNames.get(memberId)));
    }
    return members;
  }

  /**
   * The people a grant giver may ask about a protected group - the stewards of an internal group. A
   * provider group has contact points instead (#1875); until they exist, the answer for one is
   * empty rather than wrong.
   */
  private List<String> responsibleNamesOf(Group group) {
    if (!group.isInternal()) {
      return List.of();
    }
    List<UUID> userIds =
        stewardRepository.findByGroupIdOrderByCreatedAtAsc(group.getId()).stream()
            .map(GroupSteward::getUserId)
            .toList();
    Map<UUID, String> displayNames = new HashMap<>();
    for (User user : userRepository.findAllById(userIds)) {
      displayNames.put(
          user.getId(), user.getDisplayName() != null ? user.getDisplayName() : user.getEmail());
    }
    return userIds.stream().map(displayNames::get).filter(Objects::nonNull).toList();
  }
}
