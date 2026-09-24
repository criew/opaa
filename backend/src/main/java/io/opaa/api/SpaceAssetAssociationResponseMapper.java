package io.opaa.api;

import io.opaa.api.dto.AssetSpaceAssociationResponse;
import io.opaa.api.dto.AssetType;
import io.opaa.api.dto.SpaceAssetAssociationListResponse;
import io.opaa.api.dto.SpaceAssetAssociationResponse;
import io.opaa.space.AssetSpaceLink;
import io.opaa.space.SpaceAssetAssociation;
import io.opaa.space.SpaceAssetLink;
import io.opaa.space.SpaceAssetLinks;
import java.util.List;

/**
 * Maps {@link SpaceAssetLink}, {@link SpaceAssetLinks} and {@link AssetSpaceLink} onto their
 * generated response counterparts (ADR-0006: API DTOs are generated from the specification, never
 * hand-written).
 */
final class SpaceAssetAssociationResponseMapper {

  private SpaceAssetAssociationResponseMapper() {}

  static SpaceAssetAssociationResponse toResponse(SpaceAssetLink link) {
    SpaceAssetAssociation association = link.association();
    return new SpaceAssetAssociationResponse(
            AssetType.fromValue(link.assetType().value()),
            association.getAssetId(),
            link.readableByCaller(),
            association.getCreatedByUserId(),
            association.getCreatedAt())
        .name(link.name())
        .description(link.description())
        .createdByDisplayName(link.createdByDisplayName());
  }

  static SpaceAssetAssociationListResponse toListResponse(SpaceAssetLinks links) {
    List<SpaceAssetAssociationResponse> items =
        links.items().stream().map(SpaceAssetAssociationResponseMapper::toResponse).toList();
    return new SpaceAssetAssociationListResponse(
        links.hasAssociations(), links.narrowsSearch(), items);
  }

  /**
   * Below MANAGER the entry carries the space alone (#1939): {@link
   * AssetSpaceLink#managementDetail} is the one place that decides it, so a reduced link can never
   * leak a field through this mapper.
   */
  static AssetSpaceAssociationResponse toAssetSpaceResponse(AssetSpaceLink link) {
    SpaceAssetAssociation association = link.association();
    AssetSpaceAssociationResponse response =
        new AssetSpaceAssociationResponse(association.getSpaceId(), link.spaceName());
    if (!link.managementDetail()) {
      return response;
    }
    return response
        .createdByUserId(association.getCreatedByUserId())
        .createdAt(association.getCreatedAt())
        .narrowerReaderCircle(link.narrowerReaderCircle())
        .createdByDisplayName(link.createdByDisplayName());
  }

  static List<AssetSpaceAssociationResponse> toAssetSpaceResponses(List<AssetSpaceLink> links) {
    return links.stream().map(SpaceAssetAssociationResponseMapper::toAssetSpaceResponse).toList();
  }
}
