package io.opaa.library;

/**
 * The operation that opened or closed an {@link AssetGrantHistory} interval (#238) - every interval
 * carries one, so a Stichtag reconstruction can say not just "this grant existed" but "because it
 * was granted" or "because it was role-changed". Mirrored by the database check constraint {@code
 * chk_asset_grant_history_cause} (migration 018).
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
   * The interval was written by migration 018's backfill changeSet for a grant that already existed
   * before this feature - not observed as a live change, reconstructed from {@code
   * asset_grants.created_at}/{@code granted_by_user_id} so the negative question is answerable for
   * the entire existing body of grants, not only ones created after this migration ran (code review
   * of #238, finding 1).
   */
  BACKFILL,

  /**
   * Closes an open interval because the library it grants access to was deleted ({@code
   * KnowledgeLibraryService#deleteLibrary}) - closes only, like {@link #REVOKED}. Without this, a
   * deleted library's still-open grant intervals kept reporting "currently readable" for a library
   * that no longer exists (code review of #427, nit 3): library_id/subject_group_id carry no
   * foreign key (see {@code 018-permission-history.yaml}'s "Deletion survival" comment), so
   * deleting the library never closed them on its own.
   */
  LIBRARY_DELETED
}
