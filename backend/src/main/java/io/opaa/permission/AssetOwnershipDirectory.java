package io.opaa.permission;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who owns which assets - asked before a group is deleted, so a group that owns something is
 * refused with a clean conflict instead of a foreign-key violation, and asked again by {@link
 * PermissionTransferService} to move that ownership in one operation (ADR-0036, Entscheidungen 10
 * und 12). One implementation answers for every asset type of the asset shell, another for the
 * space; a directory names the types it answers for per id, never as a fixed list.
 */
public interface AssetOwnershipDirectory {

  /** Whether this directory answers for assets of {@code assetType}. */
  boolean answersFor(AssetType assetType);

  /** Whether at least one asset this directory answers for is owned by the given group. */
  boolean existsAssetOwnedByGroup(UUID groupId);

  /**
   * The German sentence shown when {@link #existsAssetOwnedByGroup} refuses the deletion - it names
   * the holdings of this group that are actually in the way.
   */
  String ownedAssetConflictMessage(UUID groupId);

  /**
   * How many assets the subject owns - a plain count, so the work limit of a transfer can be
   * decided before anything is loaded.
   */
  long countAssetsOwnedBy(PermissionSubject owner);

  /**
   * The same figure for a whole list of groups in one query - what an overview over every group of
   * an organization needs instead of a count per group. Groups owning nothing are absent from the
   * map; a directory whose assets cannot be group-owned answers with an empty one.
   */
  Map<UUID, Long> countAssetsOwnedByGroups(Collection<UUID> groupIds, UUID organizationId);

  /**
   * Every asset the subject owns, by type. A directory whose assets cannot be owned by this kind of
   * subject answers with an empty map rather than refusing - a space owner is always a natural
   * person, and that is not an error the caller has to know about.
   */
  Map<AssetType, List<UUID>> assetIdsOwnedBy(PermissionSubject owner);

  /**
   * Hands one asset to {@code newOwner}, writing the ownership interval with the boundary and the
   * transfer id the whole operation shares. Called only for ids this directory itself returned from
   * {@link #assetIdsOwnedBy}, inside the transfer's transaction.
   */
  void transferOwnership(
      AssetType assetType,
      UUID assetId,
      PermissionSubject newOwner,
      UUID actorUserId,
      UUID transferId,
      Instant at);
}
