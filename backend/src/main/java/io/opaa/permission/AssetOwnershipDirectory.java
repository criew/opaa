package io.opaa.permission;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who owns the assets of one type - asked before a group is deleted, so a group that owns something
 * is refused with a clean conflict instead of a foreign-key violation, and asked again by {@link
 * PermissionTransferService} to move that ownership in one operation. Every business package with
 * an owned asset type contributes one implementation; a further asset type extends both by adding a
 * bean, not by adding a branch (ADR-0036, Entscheidungen 10 und 12).
 */
public interface AssetOwnershipDirectory {

  /** The asset type this directory answers for - what a transfer records as the touched object. */
  AssetType assetType();

  /** Whether at least one asset of this directory's type is owned by the given group. */
  boolean existsAssetOwnedByGroup(UUID groupId);

  /**
   * The German sentence shown when {@link #existsAssetOwnedByGroup} refuses the deletion - each
   * asset type names its own holdings, so the message says what is actually in the way.
   */
  String ownedAssetConflictMessage();

  /**
   * How many assets of this type the subject owns - a plain count, so the work limit of a transfer
   * can be decided before anything is loaded.
   */
  long countAssetsOwnedBy(PermissionSubject owner);

  /**
   * The same figure for a whole list of groups in one query - what an overview over every group of
   * an organization needs instead of a count per group. Groups owning nothing of this type are
   * absent from the map; a type that cannot be group-owned answers with an empty one.
   */
  Map<UUID, Long> countAssetsOwnedByGroups(Collection<UUID> groupIds, UUID organizationId);

  /**
   * Every asset of this type the subject owns. A type that cannot be owned by this kind of subject
   * answers with an empty list rather than refusing - a space owner is always a natural person, and
   * that is not an error the caller has to know about.
   */
  List<UUID> assetIdsOwnedBy(PermissionSubject owner);

  /**
   * Hands one asset of this type to {@code newOwner}, writing the ownership interval with the
   * boundary and the transfer id the whole operation shares. Called only for ids this directory
   * itself returned from {@link #assetIdsOwnedBy}, inside the transfer's transaction.
   */
  void transferOwnership(
      UUID assetId, PermissionSubject newOwner, UUID actorUserId, UUID transferId, Instant at);
}
