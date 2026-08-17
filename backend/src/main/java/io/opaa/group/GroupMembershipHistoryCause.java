package io.opaa.group;

/**
 * The operation that opened or closed a {@link GroupMembershipHistory} interval (#238). Mirrored by
 * the database check constraint {@code chk_group_membership_history_cause} (migration 018).
 *
 * <p>The directory-sync causes exist separately from the manual ones because a synchronisation run
 * has no acting user and is, per
 * docs/features/spaces-and-assets.md#verzeichnissynchronisation-als-rechteereignis, "ein
 * Massen-Rechteentzug ohne menschlichen Entscheidungspunkt" - a distinction worth keeping in the
 * history even though both pairs otherwise mean the same thing (a membership starting or ending).
 */
public enum GroupMembershipHistoryCause {
  /** A user was added to a group through {@code GroupService#addMember}. */
  ADDED,

  /** A user was removed from a group through {@code GroupService#removeMember}. */
  REMOVED,

  /** A directory sync run added a membership ({@code DirectorySyncPlanExecutor#applyPlan}). */
  DIRECTORY_SYNC_ADDED,

  /** A directory sync run removed a membership ({@code DirectorySyncPlanExecutor#applyPlan}). */
  DIRECTORY_SYNC_REMOVED,

  /**
   * The interval was written by migration 018's backfill changeSet for a membership that already
   * existed before this feature - reconstructed from {@code group_memberships.created_at}, with no
   * actor (that column does not record who added an existing membership; code review of #238,
   * finding 1).
   */
  BACKFILL,

  /**
   * Closes an open interval because the group itself was deleted ({@code GroupService#deleteGroup})
   * - closes only, like {@link #REMOVED}. Without this, a deleted group's still-open membership
   * intervals kept reporting "currently a member" of a group that no longer exists (code review of
   * #427, nit 3): {@code group_id} carries no foreign key (see {@code
   * io.opaa.library.PermissionHistoryService}'s "Deletion survival" comment on {@code
   * 018-permission-history.yaml}), so deleting the group never closed them on its own.
   */
  GROUP_DELETED
}
