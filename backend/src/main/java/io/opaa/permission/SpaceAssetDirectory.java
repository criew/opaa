package io.opaa.permission;

import io.opaa.auth.CurrentUser;
import java.util.Set;
import java.util.UUID;

/**
 * Which assets of a type a space shows - the port that lets an asset type bound its offer by a
 * space without knowing {@code io.opaa.space} (ADR-0036, Entscheidung 12). Implemented by {@code
 * SpaceAssetDirectoryAdapter}.
 */
public interface SpaceAssetDirectory {

  /**
   * Every asset of {@code assetType} associated with the space, for a member of it, without a
   * rights filter of its own. An unknown space or one of another organization is a {@code 404}, a
   * caller who is no member a {@code 403}.
   */
  Set<UUID> assetIdsInSpace(UUID spaceId, AssetType assetType, CurrentUser caller);
}
