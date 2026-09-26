package io.opaa.space;

/**
 * One asset's view of one space it is associated with (#203), enriched with the space's name and
 * whether at least one of its members cannot themselves read the asset. Domain counterpart of the
 * generated {@code AssetSpaceAssociationResponse}, mapped by {@code
 * SpaceAssetAssociationResponseMapper}.
 *
 * <p>{@code managementDetail} says whether the caller may see more than the space's name and id:
 * below {@link io.opaa.api.types.AssetRole#MANAGER} the reader circle, the creator and the creation
 * time are neither resolved nor mapped (#1939).
 */
public record AssetSpaceLink(
    SpaceAssetAssociation association,
    String spaceName,
    boolean narrowerReaderCircle,
    String createdByDisplayName,
    boolean managementDetail) {}
