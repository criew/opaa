package io.opaa.space;

import io.opaa.api.types.SpaceRole;
import io.opaa.permission.GroupMembershipHistoryRepository;
import io.opaa.permission.PermissionHistoryClock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records and reconstructs the space-membership history (#1815, ADR-0036 Entscheidung 8): every
 * admission, role change and removal becomes a half-open interval with the operation that caused
 * it, so "was X a member of space Z on day Y" - and above all its negation - stays answerable after
 * the audit log's own retention period has taken the event away.
 *
 * <p>Every recording method runs inside the caller's own transaction (default propagation): the
 * membership change and its interval commit or roll back together. The interval contract is the one
 * {@code io.opaa.permission.PermissionHistoryService} states, and it holds across tables because
 * both share the one {@link PermissionHistoryClock}.
 *
 * <p><b>The reading path is not here.</b> {@link #spaceIdsAsOf} answers the reconstruction, but the
 * endpoint that turns it into an Auskunft - with the authorisation, the time window and the
 * retrieval event ADR-0036, Entscheidung 8 requires - belongs to #1822. Like every other
 * reconstruction, an {@code asOf} before the retention cutoff yields an <b>empty</b> answer, not a
 * negative one; the caller that publishes it owes that distinction.
 */
@Service
public class SpaceMembershipHistoryService {

  private final SpaceMembershipHistoryRepository repository;
  private final GroupMembershipHistoryRepository groupMembershipHistory;
  private final PermissionHistoryClock clock;

  SpaceMembershipHistoryService(
      SpaceMembershipHistoryRepository repository,
      GroupMembershipHistoryRepository groupMembershipHistory,
      PermissionHistoryClock clock) {
    this.repository = repository;
    this.groupMembershipHistory = groupMembershipHistory;
    this.clock = clock;
  }

  /** Opens the first interval for a newly admitted member. */
  public void recordAdded(SpaceMembership membership, UUID actorUserId) {
    repository.save(
        SpaceMembershipHistory.open(
            membership, SpaceMembershipHistoryCause.ADDED, actorUserId, clock.nextBoundary()));
  }

  /**
   * Closes the currently open interval (which must already carry the <em>new</em> role - callers
   * apply the change to the entity first) and opens the next one. A membership that predates this
   * table has no open interval; only the new one is then written, and the history is incomplete for
   * it rather than broken.
   */
  public void recordRoleChanged(SpaceMembership membership, UUID actorUserId) {
    Instant now = clock.nextBoundary();
    closeOpenInterval(membership, now);
    repository.save(
        SpaceMembershipHistory.open(
            membership, SpaceMembershipHistoryCause.ROLE_CHANGED, actorUserId, now));
  }

  /**
   * Closes the open interval of a membership that is about to be removed (keeping that interval's
   * own recorded cause) and writes the zero-length marker that records the removal itself. Call
   * before the row is deleted; {@code membership} must still carry its last role.
   */
  public void recordRemoved(SpaceMembership membership, UUID actorUserId) {
    recordEnd(membership, SpaceMembershipHistoryCause.REMOVED, actorUserId);
  }

  /**
   * The space-deletion counterpart of {@link #recordRemoved}. Required because {@code space_id}
   * carries no foreign key (ADR-0016): the deletion never closes these intervals on its own, and a
   * deleted space's memberships would keep reporting "currently a member".
   */
  public void recordSpaceDeleted(SpaceMembership membership, UUID actorUserId) {
    recordEnd(membership, SpaceMembershipHistoryCause.SPACE_DELETED, actorUserId);
  }

  /**
   * The source's side of a transfer (#1834, ADR-0036 Entscheidung 10): closes the open interval at
   * the boundary the whole transfer shares and writes the {@link
   * SpaceMembershipHistoryCause#TRANSFERRED_OUT} marker naming the operation. Call before the
   * source's membership row is deleted; {@code membership} must still carry its last role.
   */
  public void recordTransferredOut(
      SpaceMembership membership, UUID actorUserId, UUID transferId, Instant at) {
    SpaceRole lastRole = membership.getRole();
    closeOpenInterval(membership, at);
    SpaceMembershipHistory marker =
        SpaceMembershipHistory.terminal(
            membership, lastRole, SpaceMembershipHistoryCause.TRANSFERRED_OUT, actorUserId, at);
    marker.belongsToTransfer(transferId);
    repository.save(marker);
  }

  /**
   * The target's side of {@link #recordTransferredOut} - closes whatever interval the target
   * already held in that space at the same instant and opens the one it holds from then on.
   */
  public void recordTransferredIn(
      SpaceMembership membership, UUID actorUserId, UUID transferId, Instant at) {
    closeOpenInterval(membership, at);
    SpaceMembershipHistory opened =
        SpaceMembershipHistory.open(
            membership, SpaceMembershipHistoryCause.TRANSFERRED_IN, actorUserId, at);
    opened.belongsToTransfer(transferId);
    repository.save(opened);
  }

  /**
   * Every space {@code userId} reached at {@code asOf} - through a membership of their own or
   * through a group they belonged to at that same instant, resolved from {@code
   * group_membership_history} rather than from today's memberships.
   *
   * <p><b>One space is missing on purpose:</b> the personal space provisioned at first sign-in
   * carries no interval at all - see {@link SpaceRepository#insertDefaultSpaceIfAbsent} for why.
   */
  @Transactional(readOnly = true)
  public Set<UUID> spaceIdsAsOf(UUID userId, UUID organizationId, Instant asOf) {
    Set<UUID> spaceIds =
        new HashSet<>(repository.findSpaceIdsByDirectMembershipAsOf(userId, organizationId, asOf));
    Set<UUID> groupIds =
        groupMembershipHistory.findGroupIdsByUserIdAsOf(userId, organizationId, asOf);
    if (!groupIds.isEmpty()) {
      spaceIds.addAll(repository.findSpaceIdsByGroupMembershipAsOf(groupIds, organizationId, asOf));
    }
    return spaceIds;
  }

  private void recordEnd(
      SpaceMembership membership, SpaceMembershipHistoryCause cause, UUID actorUserId) {
    Instant now = clock.nextBoundary();
    SpaceRole lastRole = membership.getRole();
    closeOpenInterval(membership, now);
    repository.save(SpaceMembershipHistory.terminal(membership, lastRole, cause, actorUserId, now));
  }

  /**
   * Closes the open interval, if any, and flushes immediately - not left to the transaction's flush
   * at commit. Hibernate runs every queued insert before every queued update, so without this the
   * new row's {@code INSERT} would reach Postgres before the old row's {@code UPDATE ... SET
   * valid_to} and transiently violate the "at most one open interval" unique index.
   */
  private void closeOpenInterval(SpaceMembership membership, Instant now) {
    UUID spaceId = membership.getSpace().getId();
    Optional<SpaceMembershipHistory> open =
        membership.isUserSubject()
            ? repository.findBySpaceIdAndSubjectUserIdAndValidToIsNull(
                spaceId, membership.getUserId())
            : repository.findBySpaceIdAndSubjectGroupIdAndValidToIsNull(
                spaceId, membership.getGroupId());
    open.ifPresent(
        interval -> {
          interval.close(now);
          repository.saveAndFlush(interval);
        });
  }
}
