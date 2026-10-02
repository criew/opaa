package io.opaa.space;

import io.opaa.permission.AssetType;
import java.util.UUID;

/** One asset to associate with a space as it is created - see {@link SpaceCreation#assets()}. */
public record SpaceAssetSeed(AssetType assetType, UUID assetId) {}
