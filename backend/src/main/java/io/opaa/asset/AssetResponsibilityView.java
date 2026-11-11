package io.opaa.asset;

import io.opaa.api.types.AssetLockCause;
import java.time.Instant;
import java.util.UUID;

/**
 * The responsibility of an agent or a document template as a reader sees it.
 *
 * @param groupName the group's name as the reader may see it, {@code null} without a group
 * @param missing whether the responsibility is missing right now: no unit, a group that no longer
 *     acts, or an owner in open succession
 * @param missingSince since when the installation counts it missing, {@code null} while it is not
 *     or before the next check
 * @param lockDueAt when the object is locked unless a unit is named before; {@code null} without a
 *     running period or while it is locked already
 * @param lock {@code null} while the object is not locked
 */
public record AssetResponsibilityView(
    UUID groupId,
    String groupName,
    String function,
    boolean missing,
    Instant missingSince,
    Instant lockDueAt,
    Lock lock) {

  /** A lock with its cause and reason. */
  public record Lock(AssetLockCause cause, String reason, Instant lockedAt) {}
}
