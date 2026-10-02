package io.opaa.asset;

import io.opaa.api.types.CatalogVisibility;
import io.opaa.permission.AssetType;
import java.util.Objects;

/**
 * What one catalog request selects; every filter only narrows the readable set.
 *
 * @param assetType only this type, or every served type when {@code null}.
 * @param text part of the name or the description, matched literally and case-insensitively; blank
 *     or {@code null} matches everything.
 * @param visibility only this visibility, or both when {@code null}.
 * @param fromMyGroups only assets granted to or owned by a group the caller belongs to.
 * @param favoritesOnly only the caller's own favorites.
 */
public record AssetCatalogQuery(
    AssetType assetType,
    String text,
    CatalogVisibility visibility,
    boolean fromMyGroups,
    AssetCatalogSort sort,
    boolean favoritesOnly) {

  public AssetCatalogQuery {
    Objects.requireNonNull(sort, "sort");
  }

  /** Without the favorites filter. */
  public AssetCatalogQuery(
      AssetType assetType,
      String text,
      CatalogVisibility visibility,
      boolean fromMyGroups,
      AssetCatalogSort sort) {
    this(assetType, text, visibility, fromMyGroups, sort, false);
  }

  /** Every readable asset of {@code assetType} matching {@code text}, ordered by name. */
  public static AssetCatalogQuery of(AssetType assetType, String text) {
    return new AssetCatalogQuery(assetType, text, null, false, AssetCatalogSort.NAME);
  }
}
