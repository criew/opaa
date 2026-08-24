package io.opaa.api;

import io.opaa.api.dto.GroupListResponse;
import io.opaa.api.dto.GroupMemberResponse;
import io.opaa.api.dto.GroupResponse;
import io.opaa.group.Group;
import io.opaa.group.GroupDetail;
import io.opaa.group.GroupMemberView;
import java.util.List;

/**
 * Maps {@link Group}, {@link GroupDetail} and {@link GroupMemberView} onto their generated response
 * counterparts (ADR-0006: API DTOs are generated from the specification, never hand-written).
 */
final class GroupResponseMapper {

  private GroupResponseMapper() {}

  static GroupListResponse toListResponse(Group group) {
    return new GroupListResponse(
            group.getId(),
            group.getName(),
            group.getKind(),
            group.getMemberships().size(),
            group.getCreatedAt(),
            group.getUpdatedAt())
        .description(group.getDescription())
        .externalId(group.getExternalId())
        .parentGroupId(group.getParentGroupId());
  }

  static List<GroupListResponse> toListResponses(List<Group> groups) {
    return groups.stream().map(GroupResponseMapper::toListResponse).toList();
  }

  static GroupResponse toResponse(GroupDetail detail) {
    Group group = detail.group();
    List<GroupMemberResponse> members = toMemberResponses(detail.members());
    return new GroupResponse(
            group.getId(),
            group.getName(),
            group.getKind(),
            members.size(),
            members,
            group.getCreatedAt(),
            group.getUpdatedAt())
        .description(group.getDescription())
        .externalId(group.getExternalId())
        .parentGroupId(group.getParentGroupId());
  }

  static GroupMemberResponse toMemberResponse(GroupMemberView view) {
    return new GroupMemberResponse(view.membership().getUserId(), view.membership().getCreatedAt())
        .displayName(view.displayName());
  }

  static List<GroupMemberResponse> toMemberResponses(List<GroupMemberView> views) {
    return views.stream().map(GroupResponseMapper::toMemberResponse).toList();
  }
}
