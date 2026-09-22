package io.opaa.api;

import io.opaa.api.dto.SpaceListResponse;
import io.opaa.api.dto.SpaceMemberResponse;
import io.opaa.api.dto.SpaceResponse;
import io.opaa.api.types.SpaceRole;
import io.opaa.permission.GroupSizeSignal;
import io.opaa.permission.PermissionTransferMark;
import io.opaa.permission.SuccessionFinding;
import io.opaa.space.Space;
import io.opaa.space.SpaceDetail;
import io.opaa.space.SpaceMemberView;
import io.opaa.space.SpaceMembership;
import io.opaa.space.SpaceOverview;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps {@link SpaceDetail}, {@link SpaceOverview} and {@link SpaceMemberView} onto their generated
 * response counterparts (ADR-0006: API DTOs are generated from the specification, never
 * hand-written). Pure: every derived value - the caller's effective role, the derived state
 * "Nachfolge offen", a group's size signal - arrives already computed on the domain record, so no
 * authorization decision is taken here.
 */
final class SpaceResponseMapper {

  private SpaceResponseMapper() {}

  static SpaceResponse toResponse(SpaceDetail detail) {
    return toResponse(detail, null, null);
  }

  /**
   * The detail view additionally names the transfer that last touched this space (#1834, ADR-0036
   * Entscheidung 10), or nothing if none ever did.
   */
  static SpaceResponse toResponse(
      SpaceDetail detail, PermissionTransferMark lastTransfer, SuccessionFinding succession) {
    Space space = detail.space();
    Map<String, Long> roleCounts = new HashMap<>();
    for (SpaceRole role : SpaceRole.values()) {
      roleCounts.put(role.name(), 0L);
    }
    space.getMemberships().forEach(m -> roleCounts.merge(m.getRole().name(), 1L, Long::sum));

    // #144: the aggregated roleCounts stay visible to every member ("how big is this room"), but
    // the full member list with identities and group names is not part of SpaceResponse - it is
    // only available via listMembers, restricted to ADMIN, owner and system admins.
    //
    // #891 review: userRole is the caller's *effective* role (SpaceAccessPolicy#effectiveRole,
    // owner ⇒ at least ADMIN, and since #1815 possibly held through a group) - not their raw
    // SpaceMembership row. roleCounts and SpaceMemberResponse deliberately keep showing the *raw*
    // role of every membership row, including this caller's own.
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
        .userRole(detail.userRole())
        .successionOpen(detail.successionOpen())
        .lastTransfer(PermissionTransferResponseMapper.toResponse(lastTransfer))
        .succession(SuccessionResponseMapper.toStateResponse(succession));
  }

  static SpaceListResponse toListResponse(SpaceOverview overview) {
    Space space = overview.space();
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
        .userRole(overview.userRole())
        .successionOpen(overview.successionOpen())
        .libraryCount(overview.libraryCount())
        .chatCount(overview.chatCount());
  }

  static List<SpaceListResponse> toListResponses(List<SpaceOverview> overviews) {
    return overviews.stream().map(SpaceResponseMapper::toListResponse).toList();
  }

  static SpaceMemberResponse toMemberResponse(SpaceMemberView view) {
    SpaceMembership membership = view.membership();
    GroupSizeSignal size = view.groupSize();
    // A protected group carries no signal at all, not even "not small, not empty" - every figure
    // about it is withheld (ADR-0036, Entscheidung 9).
    boolean signals = membership.isGroupSubject() && !view.protectedGroup();
    return new SpaceMemberResponse(
            membership.getId(),
            membership.getSubjectType(),
            membership.subjectId(),
            membership.getRole(),
            membership.getCreatedAt())
        .displayName(view.displayName())
        .memberCountAtGrant(size.memberCountAtGrant())
        .memberCountNow(size.memberCountNow())
        .smallGroup(signals ? size.smallGroup() : null)
        .emptyGroup(signals ? size.emptyGroup() : null)
        .protectedGroup(membership.isGroupSubject() ? view.protectedGroup() : null);
  }

  static List<SpaceMemberResponse> toMemberResponses(List<SpaceMemberView> views) {
    return views.stream().map(SpaceResponseMapper::toMemberResponse).toList();
  }
}
