package io.opaa.space;

/**
 * Why a {@link SpaceMembershipHistory} interval was opened or closed. Mirrored by the database
 * check constraint {@code chk_space_membership_history_cause}; keep both in sync.
 */
public enum SpaceMembershipHistoryCause {
  ADDED,
  ROLE_CHANGED,
  REMOVED,
  /** Written once, by a data migration, for the memberships that existed before this table. */
  BACKFILL,
  /**
   * The space itself was deleted. {@code space_id} carries no foreign key (ADR-0016), so nothing
   * but the application closes the intervals a deleted space leaves behind.
   */
  SPACE_DELETED,

  /** The membership was handed to another subject by a transfer (#1834) - closes the source. */
  TRANSFERRED_OUT,

  /** The counterpart of {@link #TRANSFERRED_OUT}: the interval the target holds from then on. */
  TRANSFERRED_IN
}
