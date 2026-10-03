package io.opaa.asset;

import io.opaa.permission.AssetType;

/**
 * What one catalog request selects; every filter only narrows the readable set. The order is fixed:
 * the caller's favorites first, then by name.
 *
 * @param assetType only this type, or every served type when {@code null}.
 * @param text part of the name or the description, matched literally and case-insensitively; blank
 *     or {@code null} matches everything.
 * @param favoritesOnly only the caller's own favorites.
 */
public record AssetCatalogQuery(AssetType assetType, String text, boolean favoritesOnly) {

  /** Every readable asset of {@code assetType} matching {@code text}. */
  public static AssetCatalogQuery of(AssetType assetType, String text) {
    return new AssetCatalogQuery(assetType, text, false);
  }
}
