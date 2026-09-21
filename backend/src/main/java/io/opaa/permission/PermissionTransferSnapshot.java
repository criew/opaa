package io.opaa.permission;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The rows one transfer would move, read once: the preview counts and prints them, the execution
 * moves them. Read only after the work limit has been checked with plain counts, so the load itself
 * is bounded (#1834).
 *
 * <p>{@code grants} carries the source's grant rows including the expired ones - they are ended
 * like every other row of the source, only not granted to the target again.
 */
record PermissionTransferSnapshot(
    List<AssetGrant> grants,
    List<GroupSpaceMembershipRef> spaceMemberships,
    List<CapabilityGrant> capabilities,
    Map<AssetType, List<UUID>> ownedAssets,
    List<UUID> stewardedGroups) {

  /** The rows the operation touches - the figure the work limit is measured against. */
  int workload() {
    return grants.size()
        + spaceMemberships.size()
        + capabilities.size()
        + ownedAssets.values().stream().mapToInt(List::size).sum()
        + stewardedGroups.size();
  }
}
