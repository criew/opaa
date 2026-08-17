package io.opaa.library;

import io.opaa.group.GroupMembership;
import io.opaa.group.GroupMembershipHistory;
import io.opaa.group.GroupMembershipHistoryCause;
import io.opaa.group.GroupMembershipHistoryRepository;
import io.opaa.group.PermissionSubjectType;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records and reconstructs the permission-state history #238 asks for: every change to an {@link
 * AssetGrant}, a {@link GroupMembership} or a {@link KnowledgeLibrary}'s visibility/listed fields
 * is written here as a half-open interval, with the operation that caused it - so the full
 * readable-library set of any user is reconstructable at any past instant, not only "now" (see
 * docs/features/spaces-and-assets.md#nachweisbarkeit-historisierung-von-rechten). Every recording
 * method runs inside the caller's own transaction (default propagation): a grant change and its
 * history row commit or roll back together, the same as any other write this class's callers
 * already make in the same transaction.
 *
 * <p>Deliberately not the event log #391/#392 are building in parallel - this class records only
 * the resulting state interval, never a stream of "who read what". It lives next to the fact tables
 * it historises (asset_grants, group_memberships, knowledge_libraries), not behind a shared
 * audit-log abstraction.
 *
 * <p><b>Writers</b> (all deferred to after the change they historise, on the same already-loaded
 * entity, never a second lookup): {@code AssetGrantService#upsertGrant}/{@code revokeGrant}, {@code
 * GroupService#addMember}/{@code removeMember}/{@code deleteGroup}, {@code
 * DirectorySyncPlanExecutor#applyPlan} (with {@link
 * GroupMembershipHistoryCause#DIRECTORY_SYNC_ADDED}/ {@link
 * GroupMembershipHistoryCause#DIRECTORY_SYNC_REMOVED} and no actor - a sync run has no acting
 * user), and {@code KnowledgeLibraryService#createLibrary}/{@code updateLibrary}/{@code
 * deleteLibrary}. The two delete paths close every open interval the deleted library/group left
 * behind ({@link AssetGrantHistoryCause#LIBRARY_DELETED}, {@link
 * LibraryVisibilityHistoryCause#LIBRARY_DELETED}, {@link
 * GroupMembershipHistoryCause#GROUP_DELETED}) - required because {@code library_id}/{@code
 * group_id}/{@code subject_group_id} carry no foreign key (see {@code
 * 018-permission-history.yaml}'s "Deletion survival" comment and ADR-0015), so the deletion itself
 * never closes them.
 *
 * <p><b>Reader:</b> {@link #readableLibraryIdsAsOf} mirrors {@link
 * LibraryAccessService#readableLibraryIds}'s formula exactly, evaluated against the three history
 * tables at a given instant instead of against the live tables at "now" - both answer the same
 * question, one for the present, one for any Stichtag.
 */
@Service
public class PermissionHistoryService {

  private final AssetGrantHistoryRepository grantHistoryRepository;
  private final GroupMembershipHistoryRepository membershipHistoryRepository;
  private final LibraryVisibilityHistoryRepository visibilityHistoryRepository;

  public PermissionHistoryService(
      AssetGrantHistoryRepository grantHistoryRepository,
      GroupMembershipHistoryRepository membershipHistoryRepository,
      LibraryVisibilityHistoryRepository visibilityHistoryRepository) {
    this.grantHistoryRepository = grantHistoryRepository;
    this.membershipHistoryRepository = membershipHistoryRepository;
    this.visibilityHistoryRepository = visibilityHistoryRepository;
  }

  // -------------------------------------------------------------------------------------------
  // Asset grants
  // -------------------------------------------------------------------------------------------

  /** Opens the first interval for a newly created {@link AssetGrant}. */
  public void recordGrantCreated(AssetGrant grant, UUID actorUserId) {
    grantHistoryRepository.save(
        AssetGrantHistory.open(grant, AssetGrantHistoryCause.GRANTED, actorUserId, Instant.now()));
  }

  /**
   * Closes the currently open interval for {@code grant} (which must already reflect the *new*
   * role/expiresAt - callers apply the change to the entity first) and opens a new one with those
   * new values. If no open interval is found (a pre-#238 grant that predates this table), the
   * closing step is a no-op and only the new interval is written - the history is deliberately
   * incomplete for grants that existed before this feature, not broken by it.
   */
  public void recordGrantRoleChanged(AssetGrant grant, UUID actorUserId) {
    Instant now = Instant.now();
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
    Instant now = Instant.now();
    closeOpenGrantInterval(grant, now);
    grantHistoryRepository.save(
        AssetGrantHistory.terminal(grant, AssetGrantHistoryCause.REVOKED, actorUserId, now));
  }

  /**
   * The library-deletion counterpart of {@link #recordGrantRevoked} - same closing/marker
   * mechanics, cause {@link AssetGrantHistoryCause#LIBRARY_DELETED} instead of {@code REVOKED}.
   * Call once per live grant on the library, before the library itself is deleted (code review of
   * #427, nit 3: {@code library_id} carries no foreign key, so a library deletion never closed
   * these intervals on its own, leaving a deleted library's grants looking "currently readable").
   */
  public void recordGrantClosedByLibraryDeletion(AssetGrant grant, UUID actorUserId) {
    Instant now = Instant.now();
    closeOpenGrantInterval(grant, now);
    grantHistoryRepository.save(
        AssetGrantHistory.terminal(
            grant, AssetGrantHistoryCause.LIBRARY_DELETED, actorUserId, now));
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
    var open =
        grant.getSubjectType() == PermissionSubjectType.USER
            ? grantHistoryRepository.findByLibraryIdAndSubjectTypeAndSubjectUserIdAndValidToIsNull(
                grant.getLibraryId(), grant.getSubjectType(), grant.getSubjectUserId())
            : grantHistoryRepository.findByLibraryIdAndSubjectTypeAndSubjectGroupIdAndValidToIsNull(
                grant.getLibraryId(), grant.getSubjectType(), grant.getSubjectGroupId());
    open.ifPresent(
        interval -> {
          interval.close(now);
          grantHistoryRepository.saveAndFlush(interval);
        });
  }

  // -------------------------------------------------------------------------------------------
  // Group memberships
  // -------------------------------------------------------------------------------------------

  public void recordMembershipAdded(
      GroupMembership membership, GroupMembershipHistoryCause cause, UUID actorUserId) {
    membershipHistoryRepository.save(
        new GroupMembershipHistory(
            membership.getGroup().getId(),
            membership.getOrganizationId(),
            membership.getUserId(),
            cause,
            actorUserId,
            Instant.now()));
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
    Instant now = Instant.now();
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
  // Library visibility
  // -------------------------------------------------------------------------------------------

  public void recordLibraryCreated(KnowledgeLibrary library, UUID actorUserId) {
    visibilityHistoryRepository.save(
        new LibraryVisibilityHistory(
            library.getId(),
            library.getOrganizationId(),
            library.getVisibility(),
            library.isListed(),
            LibraryVisibilityHistoryCause.CREATED,
            actorUserId,
            Instant.now()));
  }

  /**
   * Closes the currently open interval for {@code library} and opens a new one with its *current*
   * visibility/listed - callers apply the change to the entity first. A no-op if visibility and
   * listed are unchanged; call only when at least one actually differs.
   */
  public void recordVisibilityChanged(KnowledgeLibrary library, UUID actorUserId) {
    Instant now = Instant.now();
    visibilityHistoryRepository
        .findByLibraryIdAndValidToIsNull(library.getId())
        .ifPresent(
            interval -> {
              interval.close(now);
              visibilityHistoryRepository.saveAndFlush(interval);
            });
    visibilityHistoryRepository.save(
        new LibraryVisibilityHistory(
            library.getId(),
            library.getOrganizationId(),
            library.getVisibility(),
            library.isListed(),
            LibraryVisibilityHistoryCause.VISIBILITY_CHANGED,
            actorUserId,
            now));
  }

  /**
   * Closes the currently open interval for {@code library} (keeping its own recorded cause
   * unchanged) and additionally writes a zero-length {@link LibraryVisibilityHistory#terminal}
   * marker with {@link LibraryVisibilityHistoryCause#LIBRARY_DELETED} - see that factory's Javadoc
   * for why the closing needs its own row. Call before the library itself is deleted (code review
   * of #427, nit 3): {@code library_id} carries no foreign key, so a library deletion never closed
   * this interval on its own, leaving a deleted library's visibility looking still in effect.
   */
  public void recordVisibilityClosedByLibraryDeletion(KnowledgeLibrary library, UUID actorUserId) {
    Instant now = Instant.now();
    visibilityHistoryRepository
        .findByLibraryIdAndValidToIsNull(library.getId())
        .ifPresent(
            interval -> {
              interval.close(now);
              visibilityHistoryRepository.saveAndFlush(interval);
            });
    visibilityHistoryRepository.save(
        LibraryVisibilityHistory.terminal(
            library, LibraryVisibilityHistoryCause.LIBRARY_DELETED, actorUserId, now));
  }

  // -------------------------------------------------------------------------------------------
  // Reconstruction
  // -------------------------------------------------------------------------------------------

  /**
   * Every library id {@code userId} could have read at {@code asOf} - the same formula {@link
   * LibraryAccessService#readableLibraryIds} evaluates for "now", evaluated here against the
   * history tables for any past instant: direct grants, grants to whichever groups the user
   * belonged to at {@code asOf}, and every library that was organization-wide at {@code asOf}.
   * Answers both #238's acceptance criteria directly - "which libraries could this person read on
   * day X" is this method's return value, and the negative question "prove library Z was not among
   * them" is answered by checking its absence in that same, single reconstruction rather than by
   * the absence of a log entry, which the feature spec explicitly rejects as unprovable.
   */
  @Transactional(readOnly = true)
  public Set<UUID> readableLibraryIdsAsOf(UUID userId, UUID organizationId, Instant asOf) {
    Set<UUID> readable = new HashSet<>();
    readable.addAll(
        grantHistoryRepository.findReadableLibraryIdsByDirectGrantAsOf(
            userId, organizationId, asOf));

    Set<UUID> groupIds =
        membershipHistoryRepository.findGroupIdsByUserIdAsOf(userId, organizationId, asOf);
    if (!groupIds.isEmpty()) {
      readable.addAll(
          grantHistoryRepository.findReadableLibraryIdsByGroupGrantAsOf(
              groupIds, organizationId, asOf));
    }

    readable.addAll(
        visibilityHistoryRepository.findOrganizationWideLibraryIdsAsOf(organizationId, asOf));
    return readable;
  }
}
