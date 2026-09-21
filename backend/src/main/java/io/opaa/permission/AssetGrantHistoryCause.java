package io.opaa.permission;

/**
 * The operation that opened or closed an {@link AssetGrantHistory} interval (#238) - every interval
 * carries one, so a Stichtag reconstruction can say not just "this grant existed" but "because it
 * was granted" or "because it was role-changed". Mirrored by the database check constraint {@code
 * chk_asset_grant_history_cause}.
 */
public enum AssetGrantHistoryCause {
  /** A new {@link AssetGrant} was created ({@code AssetGrantService#upsertGrant}'s create path). */
  GRANTED,

  /**
   * An existing {@link AssetGrant}'s role or {@code expiresAt} changed ({@code
   * AssetGrantService#upsertGrant}'s update path) - closes the previous interval and opens a new
   * one with the new role.
   */
  ROLE_CHANGED,

  /** An {@link AssetGrant} was deleted ({@code AssetGrantService#revokeGrant}) - closes only. */
  REVOKED,

  /**
   * The interval was written by the #238 backfill for a grant that already existed before this
   * feature - not observed as a live change, reconstructed from {@code asset_grants.created_at}/
   * {@code granted_by_user_id} so the negative question is answerable for the entire existing body
   * of grants, not only ones created after that migration ran.
   */
  BACKFILL,

  /**
   * Closes an open interval because the asset it grants access to was deleted - closes only, like
   * {@link #REVOKED}. Without this, a deleted asset's still-open grant intervals kept reporting
   * "currently readable" for something that no longer exists: {@code asset_id}/{@code
   * subject_group_id} carry no foreign key (ADR-0016, "Objektspalten ohne FK"), so deleting the
   * asset never closes them on its own.
   *
   * <p>The name is the value stored in {@code chk_asset_grant_history_cause} since #238, from the
   * time the only asset type was a knowledge library; it means "the asset was deleted" for every
   * type. Renaming it would be a data migration over the whole history without a functional gain.
   */
  LIBRARY_DELETED,

  /**
   * The grant was handed to another subject by a transfer (#1834, ADR-0036 Entscheidung 10) -
   * closes the source's side, exactly like {@link #REVOKED}, but names the operation that did it.
   * The row carries the transfer id the target's {@link #TRANSFERRED_IN} interval carries too.
   */
  TRANSFERRED_OUT,

  /** The counterpart of {@link #TRANSFERRED_OUT}: the interval the target holds from then on. */
  TRANSFERRED_IN
}
