package io.opaa.asset;

import io.opaa.api.types.AssetOrigin;
import java.util.UUID;

/**
 * One catalog row as read from {@code assets} alone - no type table is joined, so an entry never
 * loads what an asset contains.
 */
public interface AssetCatalogRow extends OwnedAsset {

  String getDescription();

  AssetOrigin getOrigin();

  boolean isListed();

  @Override
  default UUID getOwnerId() {
    return getOwnerGroupId() != null ? getOwnerGroupId() : getOwnerUserId();
  }
}
