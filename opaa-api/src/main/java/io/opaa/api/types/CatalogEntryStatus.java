package io.opaa.api.types;

/**
 * The one state a catalog tile shows, in the same words for every asset type. {@link
 * #SUCCESSION_OPEN} is decided by the asset shell and outranks the rest, which a type reports by
 * its own measure.
 */
public enum CatalogEntryStatus {
  READY,
  UPDATING,
  UPDATE_FAILED,
  NOT_YET_AVAILABLE,
  SUCCESSION_OPEN
}
