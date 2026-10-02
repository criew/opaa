package io.opaa.asset;

import io.opaa.permission.AssetType;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The catalog facts of one asset type. The business package owning the type contributes one bean;
 * an asset of a type without one is {@link AssetCatalogStatus#READY} and carries no facts.
 */
public interface AssetCatalogFactSource {

  AssetType assetType();

  /** Facts per asset of {@code assetIds}, in a fixed number of queries for the whole page. */
  Map<UUID, AssetCatalogFacts> factsOf(Collection<UUID> assetIds);
}
