package io.opaa.asset;

import io.opaa.api.types.AssetVisibility;
import io.opaa.permission.AssetType;
import java.util.UUID;

/**
 * The shell fields of an asset without its type's own data - what a list of assets of mixed types
 * needs, read in one query without loading any type table's collections.
 */
public record AssetHeader(
    UUID id,
    AssetType assetType,
    UUID organizationId,
    String name,
    String description,
    AssetVisibility visibility) {}
