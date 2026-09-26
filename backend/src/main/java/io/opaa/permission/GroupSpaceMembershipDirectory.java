package io.opaa.permission;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Where a group is a space member - the port that lets {@code io.opaa.group} and {@code
 * io.opaa.auth.oidc} refuse a deletion with a countable reason, and {@link
 * PermissionTransferService} move those memberships, without any of them knowing {@code
 * io.opaa.space} (ADR-0036, Entscheidung 12). Implemented by {@code
 * GroupSpaceMembershipDirectoryAdapter}.
 *
 * <p>{@code space_memberships.group_id} is {@code ON DELETE RESTRICT} (changelog 043), so without
 * this port a group that is a space member would take its own deletion - and the deletion of its
 * identity provider - into a raw foreign-key violation instead of a 409 that names the work ahead.
 */
public interface GroupSpaceMembershipDirectory {

  /** The asset type of a space - what a transfer records as the touched object. */
  AssetType spaceAssetType();

  /** How many spaces one group is a member of - the count behind a transfer's work limit. */
  long countSpaceMembershipsOf(UUID groupId);

  /** Every (group, space) pair among {@code groupIds}; an empty list for an empty input. */
  List<GroupSpaceMembershipRef> spaceMembershipsOf(Collection<UUID> groupIds);

  /**
   * Moves every space membership of {@code sourceGroupId} to {@code targetGroupId} and returns the
   * spaces touched. Where the target is already a member, the stronger of the two roles stays and
   * the source's row goes - a transfer never lowers what the target already had. Both sides of
   * every moved membership are historised with {@code at} and {@code transferId}.
   */
  List<UUID> transferSpaceMemberships(
      UUID sourceGroupId, UUID targetGroupId, UUID actorUserId, UUID transferId, Instant at);
}
