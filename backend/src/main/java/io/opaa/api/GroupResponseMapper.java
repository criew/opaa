package io.opaa.api;

import io.opaa.api.dto.GroupListResponse;
import io.opaa.api.dto.GroupMemberResponse;
import io.opaa.api.dto.GroupProviderResponse;
import io.opaa.api.dto.GroupResponse;
import io.opaa.api.dto.GroupStewardResponse;
import io.opaa.api.dto.SelectableGroupResponse;
import io.opaa.api.types.GroupOrigin;
import io.opaa.group.Group;
import io.opaa.group.GroupDetail;
import io.opaa.group.GroupMemberView;
import io.opaa.group.GroupOverview;
import io.opaa.group.GroupProviderView;
import io.opaa.group.GroupStewardView;
import io.opaa.group.SelectableGroup;
import java.util.List;

/**
 * Maps the group domain records onto the generated responses. Two fields are derived rather than
 * read from a column: the origin (ADR-0036, Entscheidung 2 - a group with a provider is a PROVIDER
 * group, one without is INTERNAL) and {@code releasedForUse}, which answers "may somebody else name
 * this group as a grant subject" and is therefore true for every provider group regardless of the
 * column, which only an internal group's stewards ever set (ADR-0036, Entscheidung 9).
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
            group.isSelectableAsSubject(),
            group.isProtectedGroup(),
            toStewardResponses(overview.stewards()),
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

  static SelectableGroupResponse toSelectableResponse(SelectableGroup selectable) {
    Group group = selectable.group();
    return new SelectableGroupResponse(
            group.getId(),
            group.getName(),
            originOf(selectable.provider()),
            selectable.smallGroup(),
            selectable.emptyGroup(),
            group.isProtectedGroup(),
            selectable.selectable(),
            selectable.dissolved(),
            selectable.providerDisabled(),
            selectable.unmaintained())
        .provider(toProviderResponse(selectable.provider()))
        .sourcePath(group.getSourcePath())
        .activeMemberCount(selectable.activeMemberCount());
  }

  static List<SelectableGroupResponse> toSelectableResponses(List<SelectableGroup> groups) {
    return groups.stream().map(GroupResponseMapper::toSelectableResponse).toList();
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
            group.isSelectableAsSubject(),
            group.isProtectedGroup(),
            toStewardResponses(detail.stewards()),
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

  static GroupStewardResponse toStewardResponse(GroupStewardView view) {
    return new GroupStewardResponse(view.steward().getUserId(), view.steward().getCreatedAt())
        .displayName(view.displayName());
  }

  static List<GroupStewardResponse> toStewardResponses(List<GroupStewardView> views) {
    return views.stream().map(GroupResponseMapper::toStewardResponse).toList();
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
