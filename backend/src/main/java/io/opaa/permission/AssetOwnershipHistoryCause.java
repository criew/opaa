package io.opaa.permission;

/**
 * Why an {@link AssetOwnershipHistory} interval was opened or closed. Mirrored by the database
 * check constraint {@code chk_asset_ownership_history_cause} (changelog 052); keep both in sync.
 */
public enum AssetOwnershipHistoryCause {
  /** The asset was created with this owner. */
  CREATED,
  /** The responsibility was handed to another owner. */
  TRANSFERRED,
  /** Written once by changelog 052 for the assets that existed before this table. */
  BACKFILL,
  /**
   * The asset itself was deleted. {@code asset_id} carries no foreign key (ADR-0016), so nothing
   * but the application closes the interval a deleted asset leaves behind.
   */
  ASSET_DELETED
}
