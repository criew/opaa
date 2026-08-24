package io.opaa.api;

import io.opaa.api.dto.AssetGrantRequest;
import io.opaa.api.dto.AssetGrantResponse;
import io.opaa.library.AssetGrant;
import io.opaa.library.AssetGrantUpsert;
import io.opaa.library.AssetGrantView;
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
        .grantedByDisplayName(view.grantedByDisplayName());
  }

  static List<AssetGrantResponse> toResponses(List<AssetGrantView> views) {
    return views.stream().map(AssetGrantResponseMapper::toResponse).toList();
  }
}
