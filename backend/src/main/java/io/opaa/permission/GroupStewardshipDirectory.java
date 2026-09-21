package io.opaa.permission;

import java.util.List;
import java.util.UUID;

/**
 * Which internal groups a person is responsible for, and the one operation that hands that
 * responsibility over (ADR-0036, Entscheidungen 4 und 10). Declared here because {@link
 * PermissionTransferService} asks the question and implemented by {@code io.opaa.group}, which owns
 * the answer; the dependency direction stays {@code group} &rarr; {@code permission}.
 *
 * <p>Responsibility carries no read access, so it produces audit events and no history rows
 * (ADR-0036, Entscheidung 8) - which is why neither method takes an interval boundary.
 */
public interface GroupStewardshipDirectory {

  /** Every internal group the person is responsible for, in this organization. */
  List<UUID> stewardedGroupIds(UUID userId, UUID organizationId);

  /**
   * Makes {@code targetUserId} responsible for every group {@code sourceUserId} is responsible for
   * and releases the source, and returns the groups touched. A group the target already stewards
   * only loses the source. Each group's appointment and dismissal is an audit event naming the
   * transfer.
   */
  List<UUID> transferStewardships(
      UUID sourceUserId, UUID targetUserId, UUID organizationId, UUID actorUserId, UUID transferId);
}
