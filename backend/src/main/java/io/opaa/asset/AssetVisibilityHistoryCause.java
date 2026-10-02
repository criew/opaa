package io.opaa.asset;

/**
 * The operation that opened or closed an {@link AssetVisibilityHistory} interval (#238), mirrored
 * by {@code chk_asset_visibility_history_cause}.
 */
public enum AssetVisibilityHistoryCause {
  /** The asset was created. */
  CREATED,

  /**
   * The release for Fremdzugaenge was set or taken back - closes the previous interval and opens a
   * new one with the new state (#1731).
   */
  EXTERNAL_ACCESS_CHANGED,

  /**
   * The release stopped taking effect without anyone acting - today always the expiry of its
   * mandatory Befristung. Opens the interval of the no-longer-released state with no actor.
   */
  EXTERNAL_ACCESS_EXPIRED,

  /**
   * Written by a migration, not by an operation: the #238 backfill for an asset that existed before
   * the history did, and the #2092 rewrite of a former change of the findability flag, whose
   * interval carries the release state of its predecessor unchanged.
   */
  BACKFILL,

  /**
   * Marks that the asset itself was deleted - a zero-length marker next to the closed interval. The
   * history carries no foreign key on the asset (ADR-0016), so the deletion never closes the
   * interval on its own.
   */
  ASSET_DELETED
}
