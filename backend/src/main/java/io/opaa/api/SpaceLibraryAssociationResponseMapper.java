package io.opaa.api;

import io.opaa.api.dto.LibrarySpaceAssociationResponse;
import io.opaa.api.dto.SpaceLibraryAssociationListResponse;
import io.opaa.api.dto.SpaceLibraryAssociationResponse;
import io.opaa.space.LibrarySpaceLink;
import io.opaa.space.SpaceAssetAssociation;
import io.opaa.space.SpaceLibraryLink;
import io.opaa.space.SpaceLibraryLinks;
import java.util.List;

/**
 * Maps {@link SpaceLibraryLink}, {@link SpaceLibraryLinks} and {@link LibrarySpaceLink} onto their
 * generated response counterparts (ADR-0006: API DTOs are generated from the specification, never
 * hand-written; #860: services return domain types, mapping moves here).
 */
final class SpaceLibraryAssociationResponseMapper {

  private SpaceLibraryAssociationResponseMapper() {}

  static SpaceLibraryAssociationResponse toResponse(SpaceLibraryLink link) {
    SpaceAssetAssociation association = link.association();
    return new SpaceLibraryAssociationResponse(
            association.getLibraryId(),
            link.readableByCaller(),
            association.getCreatedByUserId(),
            association.getCreatedAt())
        .libraryName(link.libraryName())
        .createdByDisplayName(link.createdByDisplayName());
  }

  static SpaceLibraryAssociationListResponse toListResponse(SpaceLibraryLinks links) {
    List<SpaceLibraryAssociationResponse> items =
        links.items().stream().map(SpaceLibraryAssociationResponseMapper::toResponse).toList();
    return new SpaceLibraryAssociationListResponse(links.hasAssociations(), items);
  }

  static LibrarySpaceAssociationResponse toLibrarySpaceResponse(LibrarySpaceLink link) {
    SpaceAssetAssociation association = link.association();
    return new LibrarySpaceAssociationResponse(
            association.getSpaceId(),
            link.spaceName(),
            association.getCreatedByUserId(),
            association.getCreatedAt(),
            link.narrowerReaderCircle())
        .createdByDisplayName(link.createdByDisplayName());
  }

  static List<LibrarySpaceAssociationResponse> toLibrarySpaceResponses(
      List<LibrarySpaceLink> links) {
    return links.stream()
        .map(SpaceLibraryAssociationResponseMapper::toLibrarySpaceResponse)
        .toList();
  }
}
