package io.opaa.asset;

/**
 * The one state a catalog tile shows, in the same words for every asset type. {@link
 * #SUCCESSION_OPEN} is decided by the shell and outranks the rest, which a type reports by its own
 * measure through {@link AssetCatalogFacts}.
 */
public enum AssetCatalogStatus {
  READY,
  UPDATING,
  UPDATE_FAILED,
  NOT_YET_AVAILABLE,
  SUCCESSION_OPEN
}
