package io.opaa.api;

import io.opaa.api.dto.SpaceListResponse;
import io.opaa.api.dto.SpaceMemberResponse;
import io.opaa.api.dto.SpaceResponse;
import io.opaa.space.Space;
import io.opaa.space.SpaceMemberView;
import io.opaa.space.SpaceMembership;
import io.opaa.space.SpaceOverview;
import io.opaa.space.SpaceRole;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Maps {@link Space}, {@link SpaceOverview} and {@link SpaceMemberView} onto their generated
 * response counterparts (ADR-0006: API DTOs are generated from the specification, never
 * hand-written; #860: services return domain types, mapping moves here).
 */
final class SpaceResponseMapper {

  private SpaceResponseMapper() {}

  static SpaceResponse toResponse(Space space, UUID currentUserId) {
    SpaceMembership membership = userMembership(space, currentUserId);
    Map<String, Long> roleCounts = new HashMap<>();
    for (SpaceRole role : SpaceRole.values()) {
      roleCounts.put(role.name(), 0L);
    }
    space.getMemberships().forEach(m -> roleCounts.merge(m.getRole().name(), 1L, Long::sum));

    // #144: the aggregated roleCounts stay visible to every member ("how big is this room"), but
    // the full member list with identities and display names is not part of SpaceResponse anymore
    // - it is only available via listMembers, restricted to ADMIN, owner and system admins.
    return new SpaceResponse(
            space.getId(),
            space.getName(),
            space.isDefault(),
            space.isArchived(),
            space.getOwnerId(),
            space.getMemberships().size(),
            roleCounts,
            space.getCreatedAt(),
            space.getUpdatedAt())
        .description(space.getDescription())
        .visibility(space.getVisibility())
        .userRole(membership == null ? null : membership.getRole());
  }

  static SpaceListResponse toListResponse(SpaceOverview overview, UUID currentUserId) {
    Space space = overview.space();
    SpaceMembership membership = userMembership(space, currentUserId);
    return new SpaceListResponse(
            space.getId(),
            space.getName(),
            space.isDefault(),
            space.isArchived(),
            space.getMemberships().size(),
            space.getCreatedAt(),
            space.getUpdatedAt())
        .description(space.getDescription())
        .visibility(space.getVisibility())
        .userRole(membership == null ? null : membership.getRole())
        .libraryCount(overview.libraryCount())
        .chatCount(overview.chatCount());
  }

  static List<SpaceListResponse> toListResponses(
      List<SpaceOverview> overviews, UUID currentUserId) {
    return overviews.stream().map(overview -> toListResponse(overview, currentUserId)).toList();
  }

  static SpaceMemberResponse toMemberResponse(SpaceMemberView view) {
    SpaceMembership membership = view.membership();
    return new SpaceMemberResponse(
            membership.getUserId(), membership.getRole(), membership.getCreatedAt())
        .displayName(view.displayName());
  }

  static List<SpaceMemberResponse> toMemberResponses(List<SpaceMemberView> views) {
    return views.stream().map(SpaceResponseMapper::toMemberResponse).toList();
  }

  private static SpaceMembership userMembership(Space space, UUID userId) {
    return space.getMemberships().stream()
        .filter(membership -> membership.getUserId().equals(userId))
        .findFirst()
        .orElse(null);
  }
}
