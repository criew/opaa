package io.opaa.space;

import io.opaa.permission.AssetType;

/**
 * One space's view of one associated asset the caller may read (#203/#706, #1900). Domain
 * counterpart of the generated {@code SpaceAssetAssociationResponse}, mapped by {@code
 * SpaceAssetAssociationResponseMapper}.
 */
public record SpaceAssetLink(
    SpaceAssetAssociation association,
    AssetType assetType,
    String name,
    String description,
    String createdByDisplayName) {}
