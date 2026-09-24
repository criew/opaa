package io.opaa.asset;

import io.opaa.api.types.AssetOwnerType;
import io.opaa.permission.AssetType;
import java.util.UUID;

/**
 * The shell fields the owner questions of a list need - whose names to show, whose succession is
 * open - readable off a loaded {@link Asset} and off a row that reads the shell alone.
 */
public interface OwnedAsset {

  UUID getId();

  AssetType getAssetType();

  String getName();

  AssetOwnerType getOwnerType();

  UUID getOwnerUserId();

  UUID getOwnerGroupId();

  /** The owning user or group id, whichever {@link #getOwnerType} points at. */
  UUID getOwnerId();
}
