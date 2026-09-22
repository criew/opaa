package io.opaa.permission;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One group's membership as a grant giver may read it - the answer of {@link
 * GroupMemberDisclosureDirectory}, whose Javadoc carries the rule behind every withheld field
 * (#1880).
 *
 * @param name null for a protected group.
 * @param activeMemberCount null for a protected group and for a small one.
 * @param members the requested page, ordered by name so paging is stable; empty wherever the rule
 *     withholds the list.
 * @param smallGroup true below the Mindestgruppengröße - then the list and both figures are gone,
 *     and this is what the interface says instead.
 * @param responsible whom to ask about a protected group; empty for every other group.
 */
public record GroupMemberDisclosure(
    UUID groupId,
    String name,
    boolean protectedGroup,
    boolean smallGroup,
    Integer activeMemberCount,
    List<DisclosedGroupMember> members,
    List<String> responsible) {

  public GroupMemberDisclosure {
    Objects.requireNonNull(groupId, "groupId must not be null");
    members = List.copyOf(members);
    responsible = List.copyOf(responsible);
  }
}
