package io.opaa.asset;

/** The orders of the catalog; each breaks ties by id. */
public enum AssetCatalogSort {
  /** By name, case-insensitive, ascending. */
  NAME,
  /** By the asset's own last change, most recent first. */
  UPDATED_AT
}
