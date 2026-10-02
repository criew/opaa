package io.opaa.asset.web;

import io.opaa.api.dto.CatalogEntryResponse;
import io.opaa.asset.AssetCatalogFacts;
import io.opaa.permission.AssetType;

/**
 * Carries the facts of one asset type onto a catalog entry, into the response property named after
 * that type. The web layer of the business package owning the type contributes one bean.
 */
public interface CatalogFactsResponseMapper {

  AssetType assetType();

  /** Sets the type's property of {@code response} from {@code facts} of its own source. */
  void apply(AssetCatalogFacts facts, CatalogEntryResponse response);
}
