package io.opaa.api;

import io.opaa.api.dto.DisclosedGroupMemberResponse;
import io.opaa.api.dto.GroupMemberDisclosureResponse;
import io.opaa.permission.DisclosedGroupMember;
import io.opaa.permission.GroupMemberDisclosure;

/**
 * Maps the member disclosure of a granted group onto its generated response counterpart (ADR-0006:
 * API DTOs are generated from the specification, never hand-written).
 */
final class GroupMemberDisclosureResponseMapper {

  private GroupMemberDisclosureResponseMapper() {}

  static GroupMemberDisclosureResponse toResponse(GroupMemberDisclosure disclosure) {
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
