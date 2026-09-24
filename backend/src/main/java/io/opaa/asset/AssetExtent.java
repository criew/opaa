package io.opaa.asset;

import io.opaa.permission.AssetType;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * What the assets of one type hold - documents, prompts - shown as their extent in the catalog. The
 * business package owning the type contributes one bean; a type without one shows none.
 */
public interface AssetExtent {

  AssetType assetType();

  /**
   * Items per asset of {@code assetIds}, in one grouped query; an asset without items is absent.
   */
  Map<UUID, Long> itemCounts(Collection<UUID> assetIds);
}
