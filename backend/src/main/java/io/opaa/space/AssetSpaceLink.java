package io.opaa.space;

/**
 * One asset's view of one space it is associated with - the owner-facing list (#203), enriched with
 * the space's name and whether at least one of its members cannot themselves read the asset. Domain
 * counterpart of the generated {@code AssetSpaceAssociationResponse}, mapped by {@code
 * io.opaa.api.SpaceAssetAssociationResponseMapper}.
 */
public record AssetSpaceLink(
    SpaceAssetAssociation association,
    String spaceName,
    boolean narrowerReaderCircle,
    String createdByDisplayName) {}
