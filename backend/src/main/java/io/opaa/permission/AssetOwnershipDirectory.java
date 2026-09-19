package io.opaa.permission;

import java.util.UUID;

/**
 * Whether a group still owns an asset - asked before a group is deleted, so a group that owns
 * something is refused with a clean conflict instead of a foreign-key violation. Every business
 * package with an owned asset type contributes one implementation, and {@code
 * io.opaa.group.GroupService} asks all of them; a further asset type extends the check by adding a
 * bean, not by adding a branch (ADR-0036, Entscheidung 12).
 */
public interface AssetOwnershipDirectory {

  /** Whether at least one asset of this directory's type is owned by the given group. */
  boolean existsAssetOwnedByGroup(UUID groupId);

  /**
   * The German sentence shown when {@link #existsAssetOwnedByGroup} refuses the deletion - each
   * asset type names its own holdings, so the message says what is actually in the way.
   */
  String ownedAssetConflictMessage();
}
