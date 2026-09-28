package io.opaa.permission.web;

import io.opaa.api.dto.DisclosedGroupMemberResponse;
import io.opaa.api.dto.GroupMemberDisclosureResponse;
import io.opaa.permission.DisclosedGroupMember;
import io.opaa.permission.GroupMemberDisclosure;

/**
 * Maps the member disclosure of a granted group onto its generated response counterpart, for every
 * web package that discloses one (asset grants, space members).
 */
public final class GroupMemberDisclosureResponseMapper {

  private GroupMemberDisclosureResponseMapper() {}

  public static GroupMemberDisclosureResponse toResponse(GroupMemberDisclosure disclosure) {
    return new GroupMemberDisclosureResponse(
            disclosure.groupId(),
            disclosure.protectedGroup(),
            disclosure.smallGroup(),
            disclosure.members().stream()
                .map(GroupMemberDisclosureResponseMapper::toMemberResponse)
                .toList(),
            disclosure.responsible())
        .name(disclosure.name())
        .activeMemberCount(disclosure.activeMemberCount());
  }

  private static DisclosedGroupMemberResponse toMemberResponse(DisclosedGroupMember member) {
    return new DisclosedGroupMemberResponse(member.userId()).displayName(member.displayName());
  }
}
