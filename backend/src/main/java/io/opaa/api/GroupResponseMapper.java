package io.opaa.api;

import io.opaa.api.dto.GroupListResponse;
import io.opaa.api.dto.GroupMemberResponse;
import io.opaa.api.dto.GroupProviderResponse;
import io.opaa.api.dto.GroupResponse;
import io.opaa.api.types.GroupOrigin;
import io.opaa.group.Group;
import io.opaa.group.GroupDetail;
import io.opaa.group.GroupMemberView;
import io.opaa.group.GroupOverview;
import io.opaa.group.GroupProviderView;
import java.util.List;

/**
 * Maps the group domain records onto the generated responses. The origin is derived rather than
 * read from a column (ADR-0036, Entscheidung 2): a group with a provider is a PROVIDER group, one
 * without is INTERNAL.
 */
final class GroupResponseMapper {

  private GroupResponseMapper() {}

  static GroupListResponse toListResponse(GroupOverview overview) {
    Group group = overview.group();
    return new GroupListResponse(
            group.getId(),
            group.getName(),
            group.getKind(),
            originOf(overview.provider()),
            group.getMemberships().size(),
            group.getCreatedAt(),
            group.getUpdatedAt())
        .description(group.getDescription())
        .externalId(group.getExternalId())
        .provider(toProviderResponse(overview.provider()))
        .sourcePath(group.getSourcePath())
        .parentGroupId(group.getParentGroupId());
  }

  static List<GroupListResponse> toListResponses(List<GroupOverview> overviews) {
    return overviews.stream().map(GroupResponseMapper::toListResponse).toList();
  }

  static GroupResponse toResponse(GroupDetail detail) {
    Group group = detail.group();
    List<GroupMemberResponse> members = toMemberResponses(detail.members());
    return new GroupResponse(
            group.getId(),
            group.getName(),
            group.getKind(),
            originOf(detail.provider()),
            members.size(),
            members,
            group.getCreatedAt(),
            group.getUpdatedAt())
        .description(group.getDescription())
        .externalId(group.getExternalId())
        .provider(toProviderResponse(detail.provider()))
        .sourcePath(group.getSourcePath())
        .parentGroupId(group.getParentGroupId());
  }

  static GroupMemberResponse toMemberResponse(GroupMemberView view) {
    return new GroupMemberResponse(view.membership().getUserId(), view.membership().getCreatedAt())
        .displayName(view.displayName());
  }

  static List<GroupMemberResponse> toMemberResponses(List<GroupMemberView> views) {
    return views.stream().map(GroupResponseMapper::toMemberResponse).toList();
  }

  private static GroupOrigin originOf(GroupProviderView provider) {
    return provider == null ? GroupOrigin.INTERNAL : GroupOrigin.PROVIDER;
  }

  private static GroupProviderResponse toProviderResponse(GroupProviderView provider) {
    if (provider == null) {
      return null;
    }
    return new GroupProviderResponse(
            provider.id(),
            provider.displayName(),
            provider.external(),
            provider.enabled(),
            provider.mechanism())
        .directorySyncIntervalMinutes(provider.syncIntervalMinutes())
        .lastDirectorySyncAt(provider.lastSyncAt());
  }
}
