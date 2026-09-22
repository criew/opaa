package io.opaa.permission;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One group's membership as a grant giver may read it (#1880, ADR-0036 Entscheidung 9): the active
 * accounts of one page, and the total they are a window into.
 *
 * @param name null for a protected group - a name handed out here would undo the namelessness the
 *     grant list and the space member list keep.
 * @param activeMemberCount null for a protected group, where the size is itself the disclosure.
 * @param members the requested page, ordered by name so paging is stable; empty for a protected
 *     group.
 * @param responsible whom to ask about a protected group instead of reading its members; empty for
 *     every unprotected group, where the list is the answer.
 */
public record GroupMemberDisclosure(
    UUID groupId,
    String name,
    boolean protectedGroup,
    Integer activeMemberCount,
    List<DisclosedGroupMember> members,
    List<String> responsible) {

  public GroupMemberDisclosure {
    Objects.requireNonNull(groupId, "groupId must not be null");
    members = List.copyOf(members);
    responsible = List.copyOf(responsible);
  }
}
