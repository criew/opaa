package io.opaa.asset;

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
 */
public record AssetCatalogQuery(
    AssetType assetType,
    String text,
    AssetCatalogVisibility visibility,
    boolean fromMyGroups,
    AssetCatalogSort sort) {

  public AssetCatalogQuery {
    Objects.requireNonNull(sort, "sort");
  }

  /** Every readable asset of {@code assetType} matching {@code text}, ordered by name. */
  public static AssetCatalogQuery of(AssetType assetType, String text) {
    return new AssetCatalogQuery(assetType, text, null, false, AssetCatalogSort.NAME);
  }
}
