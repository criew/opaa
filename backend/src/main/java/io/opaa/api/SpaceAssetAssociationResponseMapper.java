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
    return new SpaceAssetAssociationListResponse(links.hasAssociations(), items);
  }

  static AssetSpaceAssociationResponse toAssetSpaceResponse(AssetSpaceLink link) {
    SpaceAssetAssociation association = link.association();
    return new AssetSpaceAssociationResponse(
            association.getSpaceId(),
            link.spaceName(),
            association.getCreatedByUserId(),
            association.getCreatedAt(),
            link.narrowerReaderCircle())
        .createdByDisplayName(link.createdByDisplayName());
  }

  static List<AssetSpaceAssociationResponse> toAssetSpaceResponses(List<AssetSpaceLink> links) {
    return links.stream().map(SpaceAssetAssociationResponseMapper::toAssetSpaceResponse).toList();
  }
}
