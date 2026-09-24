package io.opaa.permission;

import io.opaa.api.types.PermissionSubjectType;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records and reconstructs the permission-state history #238 asks for: every change to an {@link
 * AssetGrant}, to a group membership and to a {@link CapabilityGrant} is written here as a
 * half-open interval, with the operation that caused it - so a subject's reach is reconstructable
 * at any past instant inside the retention period, not only "now" (see
 * docs/features/security-and-compliance.md#nachweisbarkeit-historisierung-von-rechten). Every
 * recording method runs inside the caller's own transaction (default propagation): a grant change
 * and its history row commit or roll back together, the same as any other write this class's
 * callers already make in the same transaction.
 *
 * <p><b>The third source of the readable-asset formula is not here.</b> An asset's reach history is
 * shell state, not a grant, and lives in {@code io.opaa.asset.AssetVisibilityHistoryService} -
 * which composes {@link #readableAssetIdsAsOf} with its organization-wide part for any past
 * instant, the way {@link AssetAccessService} reads the release off the shell for "now". Both
 * halves share the one {@link PermissionHistoryClock}, so the interval contract below holds across
 * all three tables.
 *
 * <p><b>Interval contract</b> (#1497, ADR-0032), holding for every row written from that change on
 * - rows written before it can still carry the empty intervals it prevents, and are not repaired:
 * successive <i>state</i> intervals of the same object have strictly increasing boundaries - two
 * changes that fall into the same clock tick still get different ones, because every boundary comes
 * from {@link PermissionHistoryClock} rather than from the wall clock directly. A state interval is
 * therefore never empty, and {@code validFrom <= asOf < validTo} has a solution for every state the
 * object held inside the retention period - a closed interval that ended before {@link
 * PermissionHistoryRetentionService#retentionCutoff()} is deleted and has none (#1833). Successive
 * intervals stay gapless: closing one and opening the next share a single boundary value.
 * Zero-length rows exist on purpose, but only as event markers ({@link AssetGrantHistory#terminal},
 * {@link GroupMembershipHistory#terminal}, {@link CapabilityGrantHistory#terminal}) recording a
 * revocation or deletion; they are exempt from the strictly-increasing rule and are never selected
 * by the reconstruction. The contract orders the <i>issuing</i> of boundaries, not the commits
 * around them: that two concurrent transactions cannot leave an interleaved chain behind is what
 * the partial unique indexes on the open rows enforce, not the clock.
 *
 * <p>Deliberately not the event log #391/#392 are building in parallel - this class records only
 * the resulting state interval, never a stream of "who read what".
 *
 * <p><b>Writers</b> (all deferred to after the change they historise, on the same already-loaded
 * entity, never a second lookup): {@code AssetGrantService#upsertGrant}/{@code revokeGrant}, {@code
 * GroupService#addMember}/{@code removeMember}/{@code deleteGroup}, {@code
 * DirectorySyncPlanExecutor#applyPlan} (with {@link
 * GroupMembershipHistoryCause#DIRECTORY_SYNC_ADDED}/{@link
 * GroupMembershipHistoryCause#DIRECTORY_SYNC_REMOVED} and no actor - a sync run has no acting
 * user), {@code KnowledgeLibraryService#deleteLibrary} and {@link CapabilityService#grant}/{@link
 * CapabilityService#revoke}. The delete paths close every open interval the deleted asset/group
 * left behind ({@link AssetGrantHistoryCause#ASSET_DELETED}, {@link
 * GroupMembershipHistoryCause#GROUP_DELETED}) - required because {@code asset_id}/{@code
 * group_id}/{@code subject_group_id} carry no foreign key (ADR-0016), so the deletion itself never
 * closes them.
 */
@Service
public class PermissionHistoryService {

  private final AssetGrantHistoryRepository grantHistoryRepository;
  private final GroupMembershipHistoryRepository membershipHistoryRepository;
  private final CapabilityGrantHistoryRepository capabilityHistoryRepository;
  private final PermissionHistoryClock clock;

  PermissionHistoryService(
      AssetGrantHistoryRepository grantHistoryRepository,
      GroupMembershipHistoryRepository membershipHistoryRepository,
      CapabilityGrantHistoryRepository capabilityHistoryRepository,
      PermissionHistoryClock clock) {
    this.grantHistoryRepository = grantHistoryRepository;
    this.membershipHistoryRepository = membershipHistoryRepository;
    this.capabilityHistoryRepository = capabilityHistoryRepository;
    this.clock = clock;
  }

  // -------------------------------------------------------------------------------------------
  // Asset grants
  // -------------------------------------------------------------------------------------------

  /** Opens the first interval for a newly created {@link AssetGrant}. */
  public void recordGrantCreated(AssetGrant grant, UUID actorUserId) {
    grantHistoryRepository.save(
        AssetGrantHistory.open(
            grant, AssetGrantHistoryCause.GRANTED, actorUserId, clock.nextBoundary()));
  }

  /**
   * Closes the currently open interval for {@code grant} (which must already reflect the *new*
   * role/expiresAt - callers apply the change to the entity first) and opens a new one with those
   * new values. If no open interval is found (a pre-#238 grant that predates this table), the
   * closing step is a no-op and only the new interval is written - the history is deliberately
   * incomplete for grants that existed before this feature, not broken by it.
   */
  public void recordGrantRoleChanged(AssetGrant grant, UUID actorUserId) {
    Instant now = clock.nextBoundary();
    closeOpenGrantInterval(grant, now);
    grantHistoryRepository.save(
        AssetGrantHistory.open(grant, AssetGrantHistoryCause.ROLE_CHANGED, actorUserId, now));
  }

  /**
   * Closes the currently open interval for a revoked {@code grant} (keeping its own recorded cause,
   * e.g. {@code GRANTED}, unchanged) and additionally writes a zero-length {@link
   * AssetGrantHistory#terminal} marker with {@link AssetGrantHistoryCause#REVOKED} - see that
   * factory's Javadoc for why the revocation needs its own row. Call before the grant itself is
   * deleted; {@code grant} must still carry its last-active role/expiresAt.
   */
  public void recordGrantRevoked(AssetGrant grant, UUID actorUserId) {
    Instant now = clock.nextBoundary();
    closeOpenGrantInterval(grant, now);
    grantHistoryRepository.save(
        AssetGrantHistory.terminal(grant, AssetGrantHistoryCause.REVOKED, actorUserId, now));
  }

  /**
   * The asset-deletion counterpart of {@link #recordGrantRevoked} - same closing/marker mechanics,
   * cause {@link AssetGrantHistoryCause#ASSET_DELETED} instead of {@code REVOKED}. Call once per
   * live grant on the asset, before the asset itself is deleted: {@code asset_id} carries no
   * foreign key, so an asset deletion never closes these intervals on its own, leaving a deleted
   * asset's grants looking "currently readable".
   */
  public void recordGrantClosedByAssetDeletion(AssetGrant grant, UUID actorUserId) {
    Instant now = clock.nextBoundary();
    closeOpenGrantInterval(grant, now);
    grantHistoryRepository.save(
        AssetGrantHistory.terminal(grant, AssetGrantHistoryCause.ASSET_DELETED, actorUserId, now));
  }

  /**
   * The source's side of a transfer (#1834, ADR-0036 Entscheidung 10): closes the open interval at
   * {@code at} and writes the {@link AssetGrantHistoryCause#TRANSFERRED_OUT} marker carrying {@code
   * transferId}. Call before the source grant row is deleted, with the boundary the whole transfer
   * shares - both sides must name the same instant, so the Stichtagsauskunft shows exactly one
   * subject on every day.
   */
  public void recordGrantTransferredOut(
      AssetGrant grant, UUID actorUserId, UUID transferId, Instant at) {
    closeOpenGrantInterval(grant, at);
    AssetGrantHistory marker =
        AssetGrantHistory.terminal(grant, AssetGrantHistoryCause.TRANSFERRED_OUT, actorUserId, at);
    marker.belongsToTransfer(transferId);
    grantHistoryRepository.save(marker);
  }

  /**
   * The target's side of {@link #recordGrantTransferredOut}: closes whatever interval the target
   * already had on the asset at the same instant and opens the one it holds from then on. {@code
   * grant} must already carry the values the target holds after the transfer.
   *
   * <p><b>Called twice for the same chain at the same boundary, it corrects rather than
   * supersedes.</b> One transfer can touch a target's grant twice - the grant part moves the
   * source's role over and the ownership part then raises it to the role that goes with ownership.
   * Closing the interval just opened would leave a state interval of zero length behind that never
   * held.
   */
  public void recordGrantTransferredIn(
      AssetGrant grant, UUID actorUserId, UUID transferId, Instant at) {
    Optional<AssetGrantHistory> open = openGrantInterval(grant);
    if (open.isPresent() && at.equals(open.get().getValidFrom())) {
      AssetGrantHistory current = open.get();
      current.correctTo(grant);
      grantHistoryRepository.saveAndFlush(current);
      return;
    }
    closeOpenGrantInterval(grant, at);
    AssetGrantHistory opened =
        AssetGrantHistory.open(grant, AssetGrantHistoryCause.TRANSFERRED_IN, actorUserId, at);
    opened.belongsToTransfer(transferId);
    grantHistoryRepository.save(opened);
  }

  /**
   * Closes the open interval, if any, and flushes immediately - not left to the transaction's
   * normal flush at commit. Hibernate's default flush order runs every queued insert before every
   * queued update, so without this explicit {@code saveAndFlush}, closing the old interval and
   * opening the new one in the same transaction would send the new row's {@code INSERT} to Postgres
   * before the old row's {@code UPDATE ... SET valid_to}, transiently violating the "at most one
   * open interval" unique index even though the two operations are correctly ordered in this
   * method's own call order.
   */
  private void closeOpenGrantInterval(AssetGrant grant, Instant now) {
    openGrantInterval(grant)
        .ifPresent(
            interval -> {
              interval.close(now);
              grantHistoryRepository.saveAndFlush(interval);
            });
  }

  /** The interval the subject currently holds on this asset, if any. */
  private Optional<AssetGrantHistory> openGrantInterval(AssetGrant grant) {
    var open =
        grant.getSubjectType() == PermissionSubjectType.USER
            ? grantHistoryRepository
                .findByAssetTypeAndAssetIdAndSubjectTypeAndSubjectUserIdAndValidToIsNull(
                    grant.getAssetType(),
                    grant.getAssetId(),
                    grant.getSubjectType(),
                    grant.getSubjectUserId())
            : grantHistoryRepository
                .findByAssetTypeAndAssetIdAndSubjectTypeAndSubjectGroupIdAndValidToIsNull(
                    grant.getAssetType(),
                    grant.getAssetId(),
                    grant.getSubjectType(),
                    grant.getSubjectGroupId());
    return open;
  }

  // -------------------------------------------------------------------------------------------
  // Group memberships
  // -------------------------------------------------------------------------------------------

  public void recordMembershipAdded(
      UUID groupId,
      UUID organizationId,
      UUID userId,
      GroupMembershipHistoryCause cause,
      UUID actorUserId) {
    membershipHistoryRepository.save(
        new GroupMembershipHistory(
            groupId, organizationId, userId, cause, actorUserId, clock.nextBoundary()));
  }

  /**
   * Closes the currently open membership interval (keeping its own recorded cause unchanged) and
   * additionally writes a zero-length {@link GroupMembershipHistory#terminal} marker with {@code
   * cause} - see that factory's Javadoc for why the removal needs its own row. {@code cause} must
   * be {@link GroupMembershipHistoryCause#REMOVED} or {@link
   * GroupMembershipHistoryCause#DIRECTORY_SYNC_REMOVED}.
   */
  public void recordMembershipRemoved(
      UUID groupId,
      UUID organizationId,
      UUID userId,
      GroupMembershipHistoryCause cause,
      UUID actorUserId) {
    Instant now = clock.nextBoundary();
    membershipHistoryRepository
        .findByGroupIdAndUserIdAndValidToIsNull(groupId, userId)
        .ifPresent(
            interval -> {
              interval.close(now);
              membershipHistoryRepository.saveAndFlush(interval);
            });
    membershipHistoryRepository.save(
        GroupMembershipHistory.terminal(groupId, organizationId, userId, cause, actorUserId, now));
  }

  // -------------------------------------------------------------------------------------------
  // Capabilities
  // -------------------------------------------------------------------------------------------

  /** Opens the first interval for a newly granted {@link CapabilityGrant}. */
  public void recordCapabilityGranted(CapabilityGrant grant, UUID actorUserId) {
    capabilityHistoryRepository.save(
        CapabilityGrantHistory.open(
            grant, CapabilityGrantHistoryCause.GRANTED, actorUserId, clock.nextBoundary()));
  }

  /**
   * Closes the currently open interval of a withdrawn {@code grant} (keeping its own recorded
   * cause, e.g. {@code DELIVERED}, unchanged) and additionally writes a zero-length {@link
   * CapabilityGrantHistory#terminal} marker with {@link CapabilityGrantHistoryCause#REVOKED}. Call
   * before the grant row itself is deleted.
   */
  public void recordCapabilityRevoked(CapabilityGrant grant, UUID actorUserId) {
    Instant now = clock.nextBoundary();
    closeOpenCapabilityInterval(grant, now);
    capabilityHistoryRepository.save(
        CapabilityGrantHistory.terminal(
            grant, CapabilityGrantHistoryCause.REVOKED, actorUserId, now));
  }

  /**
   * The capability counterpart of {@link #recordGrantTransferredOut} - same contract, same shared
   * boundary. Call before the source's grant row is deleted.
   */
  public void recordCapabilityTransferredOut(
      CapabilityGrant grant, UUID actorUserId, UUID transferId, Instant at) {
    closeOpenCapabilityInterval(grant, at);
    CapabilityGrantHistory marker =
        CapabilityGrantHistory.terminal(
            grant, CapabilityGrantHistoryCause.TRANSFERRED_OUT, actorUserId, at);
    marker.belongsToTransfer(transferId);
    capabilityHistoryRepository.save(marker);
  }

  /** The capability counterpart of {@link #recordGrantTransferredIn}. */
  public void recordCapabilityTransferredIn(
      CapabilityGrant grant, UUID actorUserId, UUID transferId, Instant at) {
    closeOpenCapabilityInterval(grant, at);
    CapabilityGrantHistory opened =
        CapabilityGrantHistory.open(
            grant, CapabilityGrantHistoryCause.TRANSFERRED_IN, actorUserId, at);
    opened.belongsToTransfer(transferId);
    capabilityHistoryRepository.save(opened);
  }

  /** Flushes for the same reason {@link #closeOpenGrantInterval} does. */
  private void closeOpenCapabilityInterval(CapabilityGrant grant, Instant now) {
    var open =
        switch (grant.getSubjectType()) {
          case USER ->
              capabilityHistoryRepository
                  .findByOrganizationIdAndCapabilityAndSubjectTypeAndSubjectUserIdAndValidToIsNull(
                      grant.getOrganizationId(),
                      grant.getCapability(),
                      grant.getSubjectType(),
                      grant.getSubjectUserId());
          case GROUP ->
              capabilityHistoryRepository
                  .findByOrganizationIdAndCapabilityAndSubjectTypeAndSubjectGroupIdAndValidToIsNull(
                      grant.getOrganizationId(),
                      grant.getCapability(),
                      grant.getSubjectType(),
                      grant.getSubjectGroupId());
          case ALL_ACCOUNTS ->
              capabilityHistoryRepository
                  .findByOrganizationIdAndCapabilityAndSubjectTypeAndValidToIsNull(
                      grant.getOrganizationId(), grant.getCapability(), grant.getSubjectType());
        };
    open.ifPresent(
        interval -> {
          interval.close(now);
          capabilityHistoryRepository.saveAndFlush(interval);
        });
  }

  // -------------------------------------------------------------------------------------------
  // Reconstruction
  // -------------------------------------------------------------------------------------------

  /**
   * Every asset id of {@code assetType} that a grant to {@code userId} - directly, or to a group
   * they belonged to - covered at {@code asOf}. The grant half of the formula {@link
   * AssetAccessService#readableAssetIds} evaluates for "now", evaluated against the two history
   * tables. Whatever an asset type adds on top of grants (a library's organization-wide visibility)
   * is composed by that type's own reader.
   *
   * <p><b>Only inside the retention period</b> (#1833). A closed interval whose {@code validTo}
   * lies before {@link PermissionHistoryRetentionService#retentionCutoff()} is deleted, so an
   * {@code asOf} before that cutoff yields an <b>empty</b> answer, not a negative one: "no access"
   * and "no longer on record" are indistinguishable in the return value. Every caller that turns
   * this into an Auskunft has to compare its {@code asOf} against the cutoff first and say which of
   * the two it is - the reading path #1822 builds is the one that owes this.
   */
  @Transactional(readOnly = true)
  public Set<UUID> readableAssetIdsAsOf(
      AssetType assetType, UUID userId, UUID organizationId, Instant asOf) {
    Set<UUID> readable =
        new HashSet<>(
            grantHistoryRepository.findReadableAssetIdsByDirectGrantAsOf(
                assetType, userId, organizationId, asOf));

    Set<UUID> groupIds =
        membershipHistoryRepository.findGroupIdsByUserIdAsOf(userId, organizationId, asOf);
    if (!groupIds.isEmpty()) {
      readable.addAll(
          grantHistoryRepository.findReadableAssetIdsByGroupGrantAsOf(
              assetType, groupIds, organizationId, asOf));
    }
    return readable;
  }

  /**
   * Every grant state interval on one asset overlapping {@code [from, to)} - the object entry of
   * the Stichtagsauskunft (#1822, ADR-0036 Entscheidung 8), and the inverse of {@link
   * #readableAssetIdsAsOf}: that one asks "what did this person reach", this one "who reached this
   * object". Deliberately without an own person filter, which is what keeps it free of a Vollmacht.
   * The same retention caveat holds: what is no longer on record is simply absent.
   */
  @Transactional(readOnly = true)
  public List<AssetGrantHistory> assetGrantIntervalsBetween(
      AssetType assetType, UUID assetId, UUID organizationId, Instant from, Instant to, int limit) {
    return grantHistoryRepository.findAssetIntervalsOverlapping(
        assetType, assetId, organizationId, from, to, PageRequest.ofSize(limit));
  }

  /** The group-membership intervals overlapping {@code [from, to)}; empty input, empty answer. */
  @Transactional(readOnly = true)
  public List<GroupMembershipHistory> groupMembershipIntervalsBetween(
      Collection<UUID> groupIds, UUID organizationId, Instant from, Instant to, int limit) {
    return groupIds.isEmpty()
        ? List.of()
        : membershipHistoryRepository.findGroupIntervalsOverlapping(
            groupIds, organizationId, from, to, PageRequest.ofSize(limit));
  }
}
