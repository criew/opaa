package io.opaa.api;

import io.opaa.api.dto.AssetGrantRequest;
import io.opaa.api.dto.AssetGrantResponse;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.asset.AssetGrantUpsert;
import io.opaa.asset.AssetGrantView;
import io.opaa.permission.AssetGrant;
import io.opaa.permission.GroupSizeSignal;
import java.util.List;

/**
 * Maps {@link AssetGrantView} onto its generated response counterpart, and {@link
 * AssetGrantRequest} onto the domain-level {@link AssetGrantUpsert} (ADR-0006: API DTOs are
 * generated from the specification, never hand-written).
 */
final class AssetGrantResponseMapper {

  private AssetGrantResponseMapper() {}

  static AssetGrantUpsert toUpsert(AssetGrantRequest request) {
    return new AssetGrantUpsert(
        request.getSubjectType(),
        request.getSubjectId(),
        request.getRole(),
        request.getExpiresAt());
  }

  static AssetGrantResponse toResponse(AssetGrantView view) {
    AssetGrant grant = view.grant();
    GroupSizeSignal size = view.groupSize();
    // A protected group carries no signal at all, not even "not small, not empty" - every figure
    // about it is withheld (ADR-0036, Entscheidung 9).
    boolean isGroup =
        grant.getSubjectType() == PermissionSubjectType.GROUP && !view.protectedGroup();
    return new AssetGrantResponse(
            grant.getId(),
            grant.getSubjectType(),
            grant.getSubjectId(),
            grant.getRole(),
            grant.getCreatedAt(),
            grant.getUpdatedAt())
        .subjectDisplayName(view.subjectDisplayName())
        .expiresAt(grant.getExpiresAt())
        .grantedByUserId(grant.getGrantedByUserId())
        .grantedByDisplayName(view.grantedByDisplayName())
        .protectedGroup(
            grant.getSubjectType() == PermissionSubjectType.GROUP ? view.protectedGroup() : null)
        .memberCountAtGrant(size.memberCountAtGrant())
        .memberCountNow(size.memberCountNow())
        .smallGroup(isGroup ? size.smallGroup() : null)
        .emptyGroup(isGroup ? size.emptyGroup() : null);
  }

  static List<AssetGrantResponse> toResponses(List<AssetGrantView> views) {
    return views.stream().map(AssetGrantResponseMapper::toResponse).toList();
  }
}
