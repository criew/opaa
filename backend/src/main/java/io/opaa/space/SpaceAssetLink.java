package io.opaa.space;

import io.opaa.permission.AssetType;

/**
 * One space's view of one associated asset (#203/#706, #1900), enriched with whether the caller may
 * themselves read it and, only if so, its name and description. Domain counterpart of the generated
 * {@code SpaceAssetAssociationResponse}, mapped by {@code SpaceAssetAssociationResponseMapper}.
 */
public record SpaceAssetLink(
    SpaceAssetAssociation association,
    AssetType assetType,
    boolean readableByCaller,
    String name,
    String description,
    String createdByDisplayName) {}
