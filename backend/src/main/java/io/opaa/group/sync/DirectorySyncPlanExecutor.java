package io.opaa.group.sync;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.DirectorySyncOutcome;
import io.opaa.api.types.GroupKind;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.TrustedProvider;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.Group;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupMembershipHistoryCause;
import io.opaa.group.GroupMembershipResolver;
import io.opaa.group.GroupRepository;
import io.opaa.library.PermissionHistoryService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Computes and, when requested and plausible, applies the diff between the database and an
 * already-fetched {@link DirectorySnapshot}. Split out from {@link DirectorySyncService} so the
 * directory fetch itself - the one call that can be slow or fail on a real deployment - never runs
 * inside a database transaction (review of PR #297): {@link DirectorySyncService} is not
 * transactional at all and fetches the snapshot before ever calling in here, and a call from one
 * Spring bean to another goes through the real proxy, so {@code @Transactional} below is honoured
 * regardless of what the caller was doing - unlike a self-invoked private method on the same
 * instance would be.
 *
 * <p><b>Plan, then act.</b> {@link #buildPlan} computes the entire diff without mutating anything.
 * {@link #planOnly} and the plausibility-threshold abort path inside {@link #handle} both stop
 * there and only turn the plan into a report. Only {@link #planAndApply}, once the plan has been
 * judged plausible, calls {@link #applyPlan}.
 *
 * <p><b>Status is recorded by the caller, not here.</b> An earlier version of this class recorded
 * the outcome itself, in a {@code REQUIRES_NEW} transaction that committed before this class's own
 * transaction did - so a run that failed to apply (e.g. a directory-supplied name too long for
 * {@code groups.name}, causing the surrounding transaction to roll back at commit) could still end
 * up with a durably recorded {@code APPLIED} status, exactly the "a change was made" claim {@link
 * DirectorySyncStatus#getLastAppliedAt()} exists to be trustworthy about (review of PR #297).
 * {@link DirectorySyncService} - itself not transactional - calls {@link
 * DirectorySyncStatusRecorder} only after {@link #planAndApply} has returned successfully, so a
 * roll back here never reaches the status table at all.
 *
 * <p><b>Matching:</b> exclusively by {@link Group#getExternalId()} - the directory's stable
 * identifier, never by name (see #237's acceptance criteria; a rename must be free of side
 * effects).
 *
 * <p><b>Membership resolution:</b> flat only. A group's membership is exactly what the directory
 * reports as its direct members; a member of a child organizational unit is never treated as an
 * implicit member of the parent. Nested-group membership inheritance is called out as an open point
 * in the feature spec and is deliberately left unresolved by this service - {@code parentGroupId}
 * is recorded for #208's curator escalation only, resolved in a second pass in {@link #applyPlan}
 * so it works regardless of the order the directory reports groups in and applies to existing
 * groups too, not only newly created ones (review of PR #297).
 *
 * <p><b>Plausibility threshold ({@link #buildPlan}):</b> {@code changedFraction} is the worse of
 * two independent measures, each checked against the same configured threshold.
 *
 * <p>The first, membership-based measure has a denominator and numerator that both range over
 * exactly the same population of groups - every group this run actually touches
 * (matched-and-unchanged, renamed, membership-changed, reactivated, or about to be dissolved) - and
 * nothing else. A group that was already dissolved before this run and stays unreported keeps its
 * frozen membership out of both sides. Two things review of PR #297 found broken under the original
 * (name-only) computation are folded in explicitly: a group being dissolved this run contributes
 * its entire pre-run membership to the numerator (its reach is capped the same way a revoked grant
 * would be, even though no individual {@code group_memberships} row is deleted), and a group being
 * reactivated this run contributes its frozen pre-run membership to the denominator (it re-enters
 * "in play" in the very run that can remove from it).
 *
 * <p>The second, group-count-based measure exists because the first is blind to a mass dissolution
 * of groups that have no members at all - routine during an introduction phase, before curators and
 * memberships are populated (residual risk flagged in review of PR #297). It is the fraction of
 * groups active before this run that this run would dissolve, independent of how many members any
 * of them have - but only once the population is large enough ({@link
 * #MIN_ACTIVE_GROUPS_FOR_GROUP_MEASURE}) or the dissolution count alarming enough on its own
 * ({@link #MIN_DISSOLUTIONS_FOR_GROUP_MEASURE}) for a fraction to mean anything; below that floor a
 * single legitimate dissolution in a small organisation - the normal case for a pilot, not the
 * mass-outage case this measure targets - would otherwise abort every run indefinitely, with no
 * per-run override available (a second regression review of PR #297 found and measured).
 */
@Service
class DirectorySyncPlanExecutor {

  private static final Logger log = LoggerFactory.getLogger(DirectorySyncPlanExecutor.class);

  /**
   * Floor for the group-count plausibility measure - see where it is used in {@link #buildPlan} for
   * the reasoning (review of PR #297).
   */
  private static final int MIN_ACTIVE_GROUPS_FOR_GROUP_MEASURE = 10;

  private static final int MIN_DISSOLUTIONS_FOR_GROUP_MEASURE = 5;

  /**
   * The fixed, non-pseudonymised actor label #392's audit entries for this class use - "a sync run
   * has no acting user" (see {@link PermissionHistoryService}'s class Javadoc), so there is no
   * person's identity to protect behind a pseudonym here.
   */
  private static final String DIRECTORY_SYNC_ACTOR = "directory-sync";

  private final GroupRepository groupRepository;
  private final UserRepository userRepository;
  private final TrustedProvider trustedProvider;
  private final GroupMembershipResolver membershipResolver;
  private final DirectorySyncProperties properties;
  private final PermissionHistoryService permissionHistoryService;
  private final AuditEventRecorder auditEventRecorder;

  DirectorySyncPlanExecutor(
      GroupRepository groupRepository,
      UserRepository userRepository,
      TrustedProvider trustedProvider,
      GroupMembershipResolver membershipResolver,
      DirectorySyncProperties properties,
      PermissionHistoryService permissionHistoryService,
      AuditEventRecorder auditEventRecorder) {
    this.groupRepository = groupRepository;
    this.userRepository = userRepository;
    this.trustedProvider = trustedProvider;
    this.membershipResolver = membershipResolver;
    this.properties = properties;
    this.permissionHistoryService = permissionHistoryService;
    this.auditEventRecorder = auditEventRecorder;
  }

  // #392 code review, finding 1: this can no longer be readOnly - handle() -> finish() now writes
  // the run's DIRECTORY_SYNC_RUN_COMPLETED header entry unconditionally, including on the dry-run
  // path this method serves. A readOnly transaction sets Hibernate's flush mode to MANUAL and the
  // JDBC connection itself to read-only, so that insert would either be silently dropped at commit
  // or rejected by Postgres with "cannot execute INSERT in a read-only transaction" (500) -
  // dry-run's only other write in this method, none, made readOnly look safe until this entry
  // needed writing too.
  @Transactional
  SyncReport planOnly(UUID organizationId, Instant now, DirectorySnapshot snapshot) {
    return handle(organizationId, now, snapshot, false);
  }

  @Transactional
  SyncReport planAndApply(UUID organizationId, Instant now, DirectorySnapshot snapshot) {
    return handle(organizationId, now, snapshot, true);
  }

  private SyncReport handle(
      UUID organizationId, Instant now, DirectorySnapshot snapshot, boolean applyIfPlausible) {
    // #392: one correlation id per run, shared by the header entry below and, if the run actually
    // applies anything, by every DIRECTORY_SYNC_CHANGE_APPLIED entry applyPlan writes - "verbunden
    // ueber correlation_ref" (docs/features/security-and-compliance.md).
    UUID correlationRef = UUID.randomUUID();
    List<Group> existingOrgUnits =
        groupRepository.findByOrganizationIdAndKindOrgUnit(organizationId);

    if (snapshot.groups().isEmpty() && !existingOrgUnits.isEmpty()) {
      String message =
          "Das Verzeichnis hat eine leere Gruppenliste geliefert, obwohl "
              + existingOrgUnits.size()
              + " Organisationseinheit(en) bekannt sind. Der Lauf wurde ohne Änderungen"
              + " abgebrochen.";
      log.warn(
          "Directory sync: directory returned an empty group list for organization {} while {}"
              + " ORG_UNIT groups exist - aborting without changes",
          organizationId,
          existingOrgUnits.size());
      return finish(
          organizationId,
          correlationRef,
          now,
          DirectorySyncOutcome.ABORTED_EMPTY_RESULT,
          message,
          emptyPlan());
    }

    // ADR-0025, Entscheidung 4: the directory's subjects are accounts of the trusted provider
    // only - without one there is nobody to resolve, and resolving nobody would read as "remove
    // every membership", so the run stops here instead.
    String issuer = trustedProvider.issuer().orElse(null);
    if (issuer == null) {
      String message =
          "Kein Standardanbieter hinterlegt: Die Mitglieder des Verzeichnisses können keinem"
              + " Konto zugeordnet werden. Der Lauf wurde ohne Änderungen abgebrochen.";
      log.warn(
          "Directory sync: no trusted provider to resolve members through for organization {} -"
              + " aborting without changes",
          organizationId);
      return finish(
          organizationId,
          correlationRef,
          now,
          DirectorySyncOutcome.ABORTED_NO_TRUSTED_PROVIDER,
          message,
          emptyPlan());
    }

    SyncPlan plan = buildPlan(organizationId, issuer, snapshot, existingOrgUnits);

    if (plan.changedFraction() > properties.changeThresholdFraction()) {
      String changedPercent = formatPercent(plan.changedFraction());
      String thresholdPercent = formatPercent(properties.changeThresholdFraction());
      String message =
          String.format(
              Locale.ROOT,
              "Der Lauf würde %s der betroffenen Mitgliedschaften oder Gruppen entfernen bzw."
                  + " einfrieren und damit die konfigurierte Schwelle von %s überschreiten."
                  + " Abgebrochen ohne Änderungen.",
              changedPercent,
              thresholdPercent);
      log.warn(
          "Directory sync: run for organization {} would put {} of {} memberships and {} of {}"
              + " active groups at risk ({}, threshold {}) - aborting without changes",
          organizationId,
          plan.membershipsAtRisk(),
          plan.existingMembershipCount(),
          plan.dissolutions().size(),
          plan.activeGroupCount(),
          changedPercent,
          thresholdPercent);
      return finish(
          organizationId,
          correlationRef,
          now,
          DirectorySyncOutcome.ABORTED_THRESHOLD,
          message,
          plan);
    }

    if (!applyIfPlausible) {
      return finish(
          organizationId,
          correlationRef,
          now,
          DirectorySyncOutcome.DRY_RUN,
          "Trockenlauf - keine Änderung.",
          plan);
    }

    applyPlan(organizationId, now, plan, correlationRef);
    return finish(
        organizationId,
        correlationRef,
        now,
        DirectorySyncOutcome.APPLIED,
        "Synchronisation angewendet.",
        plan);
  }

  /**
   * Builds the report the same way {@link #buildReport} always did, and additionally writes #392's
   * header entry ({@link AuditEventType#DIRECTORY_SYNC_RUN_COMPLETED}) for this run - regardless of
   * outcome, including an aborted or dry run, since "Ergebnis" in the specification's sense is the
   * outcome of the run itself, not only a successful application. Per-change entries (written
   * inside {@link #applyPlan}, only reachable on the {@code APPLIED} path) share the same {@code
   * correlationRef}.
   */
  private SyncReport finish(
      UUID organizationId,
      UUID correlationRef,
      Instant now,
      DirectorySyncOutcome outcome,
      String message,
      SyncPlan plan) {
    SyncReport report = buildReport(now, outcome, message, plan);
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("outcome", outcome.name());
    after.put("membershipsAdded", report.membershipsAdded());
    after.put("membershipsRemoved", report.membershipsRemoved());
    auditEventRecorder.recordSystemProcessAction(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actorRef(DIRECTORY_SYNC_ACTOR)
            .type(AuditEventType.DIRECTORY_SYNC_RUN_COMPLETED)
            .object(
                AuditObjectType.DIRECTORY_SYNC_RUN,
                correlationRef,
                "Verzeichnisabgleich " + correlationRef)
            .after(after)
            .outcome(toAuditOutcome(outcome))
            .reason(message)
            .correlationRef(correlationRef.toString())
            .build());
    return report;
  }

  /**
   * #392 code review, nit 1: the header entry's own {@code outcome} column now reflects whether the
   * run actually did what it set out to do, not always {@code SUCCESS} - a filter on {@code outcome
   * != SUCCESS} would otherwise never surface an aborted run, even though the human-readable result
   * was always available in {@code after.outcome}/{@code reason}. {@code APPLIED} and {@code
   * DRY_RUN} both did exactly what they were asked (write the diff, or only compute it); {@code
   * ABORTED_THRESHOLD} and {@code ABORTED_EMPTY_RESULT} are the plausibility guard refusing to
   * write anything it judged unsafe - a failure to complete, not a permission decision, hence
   * {@code FAILURE} rather than {@code DENIED}. {@code UNREACHABLE} is handled by {@link
   * DirectorySyncService} itself, which never reaches this class - see its own header entry.
   */
  private AuditOutcome toAuditOutcome(DirectorySyncOutcome outcome) {
    return switch (outcome) {
      case APPLIED, DRY_RUN -> AuditOutcome.SUCCESS;
      case ABORTED_THRESHOLD, ABORTED_EMPTY_RESULT, ABORTED_NO_TRUSTED_PROVIDER ->
          AuditOutcome.FAILURE;
      case UNREACHABLE ->
          throw new IllegalStateException(
              "UNREACHABLE is handled by DirectorySyncService before this class ever runs");
    };
  }

  private String formatPercent(double fraction) {
    return String.format(Locale.ROOT, "%.1f%%", fraction * 100);
  }

  // ---------------------------------------------------------------------------------------
  // Plan building - read-only
  // ---------------------------------------------------------------------------------------

  private SyncPlan buildPlan(
      UUID organizationId,
      String issuer,
      DirectorySnapshot snapshot,
      List<Group> existingOrgUnits) {
    Map<String, Group> existingByExternalId = new HashMap<>();
    for (Group group : existingOrgUnits) {
      if (group.getExternalId() != null) {
        existingByExternalId.put(group.getExternalId(), group);
      }
    }

    List<PlannedCreate> creates = new ArrayList<>();
    List<PlannedRename> renames = new ArrayList<>();
    List<PlannedReactivation> reactivations = new ArrayList<>();
    List<PlannedMembershipChange> membershipChanges = new ArrayList<>();
    Set<UUID> reactivatingGroupIds = new HashSet<>();
    int membershipsAdded = 0;
    int membershipsRemoved = 0;
    int unresolvedMemberCount = 0;

    Map<String, DirectoryGroup> incomingByExternalId = new HashMap<>();
    Set<String> incomingExternalIds = new HashSet<>();
    for (DirectoryGroup incoming : snapshot.groups()) {
      if (incoming.externalId().isBlank() || incoming.name().isBlank()) {
        log.warn(
            "Directory sync: skipping malformed directory group for organization {} (blank"
                + " externalId or name)",
            organizationId);
        continue;
      }
      incomingExternalIds.add(incoming.externalId());
      incomingByExternalId.put(incoming.externalId(), incoming);

      ResolvedMembers resolved = resolveMembers(organizationId, issuer, incoming.memberSubjects());
      unresolvedMemberCount += resolved.unresolvedCount();

      Group existing = existingByExternalId.get(incoming.externalId());
      if (existing == null) {
        creates.add(new PlannedCreate(incoming, resolved.users()));
        membershipsAdded += resolved.users().size();
        continue;
      }

      if (existing.isDissolved()) {
        reactivations.add(new PlannedReactivation(existing));
        reactivatingGroupIds.add(existing.getId());
      }

      if (!existing.getName().equals(incoming.name())) {
        renames.add(new PlannedRename(existing, incoming.name()));
      }

      Set<UUID> currentMemberIds = new HashSet<>();
      for (GroupMembership membership : existing.getMemberships()) {
        currentMemberIds.add(membership.getUserId());
      }
      Map<UUID, ResolvedUserRef> desiredMembers = new HashMap<>();
      for (ResolvedUserRef user : resolved.users()) {
        desiredMembers.put(user.id(), user);
      }

      Set<ResolvedUserRef> toAdd = new HashSet<>();
      for (Map.Entry<UUID, ResolvedUserRef> entry : desiredMembers.entrySet()) {
        if (!currentMemberIds.contains(entry.getKey())) {
          toAdd.add(entry.getValue());
        }
      }
      Set<UUID> toRemoveIds = new HashSet<>(currentMemberIds);
      toRemoveIds.removeAll(desiredMembers.keySet());
      // Resolved with a display name, not left null like an earlier version of this method did
      // (review of PR #297): the report is exactly where an admin decides whether to let a run
      // through that would remove access, and "who" matters most for the direction that costs
      // rights.
      Set<ResolvedUserRef> toRemove = resolveResolvedUserRefsById(toRemoveIds);

      if (!toAdd.isEmpty() || !toRemove.isEmpty()) {
        membershipChanges.add(new PlannedMembershipChange(existing, toAdd, toRemove));
        membershipsAdded += toAdd.size();
        membershipsRemoved += toRemove.size();
      }
    }

    List<PlannedDissolution> dissolutions = new ArrayList<>();
    for (Group existing : existingOrgUnits) {
      if (!existing.isDissolved()
          && existing.getExternalId() != null
          && !incomingExternalIds.contains(existing.getExternalId())) {
        dissolutions.add(new PlannedDissolution(existing));
      }
    }

    // Denominator: every group "in play" this run - active groups, plus a dissolved group only
    // in the run that reactivates it (see the class javadoc). Numerator: removals from matched
    // groups, plus the entire pre-run membership of a group about to be dissolved - its reach is
    // capped the same way a revoked grant would be, even though no membership row is deleted.
    int existingMembershipCount = 0;
    int activeGroupCount = 0;
    for (Group group : existingOrgUnits) {
      if (!group.isDissolved() || reactivatingGroupIds.contains(group.getId())) {
        existingMembershipCount += group.getMemberships().size();
      }
      if (!group.isDissolved()) {
        activeGroupCount++;
      }
    }
    int membershipsAtRisk = membershipsRemoved;
    for (PlannedDissolution dissolution : dissolutions) {
      membershipsAtRisk += dissolution.group().getMemberships().size();
    }
    double membershipChangedFraction =
        existingMembershipCount == 0
            ? (membershipsAtRisk > 0 ? 1.0 : 0.0)
            : (double) membershipsAtRisk / existingMembershipCount;

    // A second, independent measure on the number of groups rather than their membership: an
    // introduction-phase directory routinely has ORG_UNIT groups with zero members (curators and
    // memberships are populated gradually), so a mass dissolution of empty groups is invisible to
    // membershipChangedFraction - its numerator and denominator are both driven by memberships,
    // which such a run never touches. changedFraction is the worse of the two so a run this
    // implausible in either dimension is caught, not only one measured in memberships (residual
    // risk flagged in review of PR #297).
    //
    // Only takes effect once the population is large enough to make a *fraction* meaningful
    // (MIN_ACTIVE_GROUPS_FOR_GROUP_MEASURE) or the absolute count is already alarming on its own
    // (MIN_DISSOLUTIONS_FOR_GROUP_MEASURE) - without this floor, review of PR #297 measured a
    // small, entirely legitimate organisation (3 active units, one empty placeholder correctly
    // disappearing, zero memberships at risk) getting permanently stuck at ABORTED_THRESHOLD: 1 of
    // 3 groups is 33%, above the default 30% threshold, with no per-run override available and no
    // way for the fraction to ever improve on a later run of the same organisation. A single
    // dissolved unit in a small authority - the common case for a pilot, not the exception - must
    // stay a routine, applicable change; this measure exists for the *mass* case the numbers below
    // are calibrated to.
    boolean groupMeasureApplies =
        activeGroupCount >= MIN_ACTIVE_GROUPS_FOR_GROUP_MEASURE
            || dissolutions.size() >= MIN_DISSOLUTIONS_FOR_GROUP_MEASURE;
    double groupDissolutionFraction =
        groupMeasureApplies && activeGroupCount > 0
            ? (double) dissolutions.size() / activeGroupCount
            : 0.0;
    double changedFraction = Math.max(membershipChangedFraction, groupDissolutionFraction);

    return new SyncPlan(
        existingOrgUnits,
        incomingByExternalId,
        creates,
        renames,
        reactivations,
        membershipChanges,
        dissolutions,
        membershipsAdded,
        membershipsRemoved,
        membershipsAtRisk,
        unresolvedMemberCount,
        existingMembershipCount,
        activeGroupCount,
        changedFraction);
  }

  private ResolvedMembers resolveMembers(UUID organizationId, String issuer, Set<String> subjects) {
    if (subjects.isEmpty()) {
      return new ResolvedMembers(Set.of(), 0);
    }
    List<User> users =
        userRepository.findByOrganizationIdAndIssuerAndSubjectIn(organizationId, issuer, subjects);
    Set<ResolvedUserRef> userRefs = new HashSet<>();
    for (User user : users) {
      userRefs.add(toResolvedUserRef(user));
    }
    int unresolved = subjects.size() - users.size();
    return new ResolvedMembers(userRefs, Math.max(unresolved, 0));
  }

  /**
   * Resolves already-known member ids (a group's current, persisted membership) to their display
   * names, the same way {@link #resolveMembers} does for the directory's incoming subjects. These
   * ids come from this organization's own {@code group_memberships} rows, not from external input,
   * so no organization-boundary check is needed here the way {@link
   * io.opaa.auth.UserRepository#findByOrganizationIdAndIssuerAndSubjectIn} enforces one.
   */
  private Set<ResolvedUserRef> resolveResolvedUserRefsById(Set<UUID> userIds) {
    if (userIds.isEmpty()) {
      return Set.of();
    }
    Set<ResolvedUserRef> userRefs = new HashSet<>();
    for (User user : userRepository.findAllById(userIds)) {
      userRefs.add(toResolvedUserRef(user));
    }
    return userRefs;
  }

  private ResolvedUserRef toResolvedUserRef(User user) {
    String displayName = user.getDisplayName() != null ? user.getDisplayName() : user.getEmail();
    return new ResolvedUserRef(user.getId(), displayName);
  }

  // ---------------------------------------------------------------------------------------
  // Applying the plan
  // ---------------------------------------------------------------------------------------

  private void applyPlan(UUID organizationId, Instant now, SyncPlan plan, UUID correlationRef) {
    Set<UUID> affectedUserIds = new HashSet<>();
    List<Group> createdGroups = new ArrayList<>();

    for (PlannedCreate create : plan.creates()) {
      DirectoryGroup incoming = create.directoryGroup();
      Group group =
          new Group(
              organizationId,
              GroupKind.ORG_UNIT,
              incoming.name(),
              null,
              incoming.externalId(),
              null);
      for (ResolvedUserRef member : create.members()) {
        group.addMembership(new GroupMembership(member.id(), organizationId));
        affectedUserIds.add(member.id());
        log.info(
            "Directory sync: added user {} to group {} ({}) as part of its creation",
            member.id(),
            group.getId(),
            group.getExternalId());
      }
      groupRepository.save(group);
      createdGroups.add(group);
      // #238: recorded after the save above, so the group row is already present for the history
      // row's FK - all of a newly created group's memberships are new, so every one is recorded.
      for (GroupMembership membership : group.getMemberships()) {
        permissionHistoryService.recordMembershipAdded(
            membership, GroupMembershipHistoryCause.DIRECTORY_SYNC_ADDED, null);
      }
      // #392: one DIRECTORY_SYNC_CHANGE_APPLIED entry for the group's creation itself, not one per
      // initial member - unlike an add/remove on an *existing* group (below), the group's own
      // existence is the effected change here, so a newly created group with zero initial members
      // still produces exactly one entry, never zero.
      recordSyncChange(
          organizationId,
          correlationRef,
          group,
          null,
          null,
          null,
          Map.of("created", true, "memberCount", group.getMemberships().size()));
      log.info(
          "Directory sync: created group {} ({}, external id {}) with {} member(s)",
          group.getId(),
          group.getName(),
          group.getExternalId(),
          create.members().size());
    }

    for (PlannedReactivation reactivation : plan.reactivations()) {
      reactivation.group().reactivate();
      log.info(
          "Directory sync: group {} ({}) reactivated - reported by the directory again after"
              + " having been dissolved",
          reactivation.group().getId(),
          reactivation.group().getExternalId());
      recordSyncChange(
          organizationId,
          correlationRef,
          reactivation.group(),
          null,
          null,
          Map.of("dissolved", true),
          Map.of("dissolved", false));
    }

    for (PlannedRename rename : plan.renames()) {
      log.info(
          "Directory sync: group {} ({}) renamed from '{}' to '{}'",
          rename.group().getId(),
          rename.group().getExternalId(),
          rename.group().getName(),
          rename.newName());
      String previousName = rename.group().getName();
      rename.group().renameFromDirectory(rename.newName());
      recordSyncChange(
          organizationId,
          correlationRef,
          rename.group(),
          null,
          null,
          Map.of("name", previousName),
          Map.of("name", rename.newName()));
    }

    for (PlannedMembershipChange change : plan.membershipChanges()) {
      for (ResolvedUserRef member : change.toAdd()) {
        GroupMembership membership = new GroupMembership(member.id(), organizationId);
        change.group().addMembership(membership);
        affectedUserIds.add(member.id());
        // #238: change.group() already exists in the database (unlike PlannedCreate's brand new
        // group above), so its FK is satisfied immediately.
        permissionHistoryService.recordMembershipAdded(
            membership, GroupMembershipHistoryCause.DIRECTORY_SYNC_ADDED, null);
        recordSyncChange(
            organizationId,
            correlationRef,
            change.group(),
            AuditSubjectKind.USER,
            member.id(),
            null,
            Map.of("member", true));
        log.info(
            "Directory sync: added user {} to group {} ({})",
            member.id(),
            change.group().getId(),
            change.group().getExternalId());
      }
      for (ResolvedUserRef member : change.toRemove()) {
        change.group().getMemberships().stream()
            .filter(m -> m.getUserId().equals(member.id()))
            .findFirst()
            .ifPresent(change.group()::removeMembership);
        affectedUserIds.add(member.id());
        permissionHistoryService.recordMembershipRemoved(
            change.group().getId(),
            organizationId,
            member.id(),
            GroupMembershipHistoryCause.DIRECTORY_SYNC_REMOVED,
            null);
        recordSyncChange(
            organizationId,
            correlationRef,
            change.group(),
            AuditSubjectKind.USER,
            member.id(),
            Map.of("member", true),
            null);
        log.info(
            "Directory sync: removed user {} from group {} ({})",
            member.id(),
            change.group().getId(),
            change.group().getExternalId());
      }
    }

    for (PlannedDissolution dissolution : plan.dissolutions()) {
      dissolution.group().dissolve(now);
      log.info(
          "Directory sync: group {} ({}) marked dissolved - no longer reported by the directory,"
              + " reach frozen at {} member(s)",
          dissolution.group().getId(),
          dissolution.group().getExternalId(),
          dissolution.group().getMemberships().size());
      recordSyncChange(
          organizationId,
          correlationRef,
          dissolution.group(),
          null,
          null,
          Map.of("dissolved", false),
          Map.of("dissolved", true));
    }

    List<Group> touched = new ArrayList<>();
    plan.reactivations().forEach(r -> touched.add(r.group()));
    plan.renames().forEach(r -> touched.add(r.group()));
    plan.membershipChanges().forEach(c -> touched.add(c.group()));
    plan.dissolutions().forEach(d -> touched.add(d.group()));
    if (!touched.isEmpty()) {
      groupRepository.saveAll(touched);
    }

    resolveAndApplyParentLinks(plan, createdGroups);

    if (!affectedUserIds.isEmpty()) {
      invalidateAfterCommit(() -> membershipResolver.invalidateUsers(affectedUserIds));
    }
  }

  /**
   * Writes one {@link AuditEventType#DIRECTORY_SYNC_CHANGE_APPLIED} entry for a single effected
   * change applyPlan just made to {@code group} - a membership add/remove, a rename, a
   * reactivation, or a dissolution. {@code subjectKind}/{@code subjectId} are only set for a
   * membership change; the other three kinds of change have no rights subject of their own, only
   * the group as object.
   */
  private void recordSyncChange(
      UUID organizationId,
      UUID correlationRef,
      Group group,
      AuditSubjectKind subjectKind,
      UUID subjectId,
      Map<String, Object> before,
      Map<String, Object> after) {
    if (subjectKind == null) {
      auditEventRecorder.recordSystemProcessAction(
          AuditEvent.builder()
              .organizationId(organizationId)
              .actorRef(DIRECTORY_SYNC_ACTOR)
              .type(AuditEventType.DIRECTORY_SYNC_CHANGE_APPLIED)
              .object(AuditObjectType.GROUP, group.getId(), group.getName())
              .before(before)
              .after(after)
              .outcome(AuditOutcome.SUCCESS)
              .correlationRef(correlationRef.toString())
              .build());
      return;
    }
    auditEventRecorder.recordSystemProcessAction(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actorRef(DIRECTORY_SYNC_ACTOR)
            .type(AuditEventType.DIRECTORY_SYNC_CHANGE_APPLIED)
            .object(AuditObjectType.GROUP, group.getId(), group.getName())
            .subject(subjectKind, subjectId)
            .before(before)
            .after(after)
            .outcome(AuditOutcome.SUCCESS)
            .correlationRef(correlationRef.toString())
            .build());
  }

  /**
   * Resolves every group's parent link in a second pass, after every group touched this run - new
   * or existing - has a persisted id. Fixes two defects review of PR #297 found in the original
   * single-pass version: it no longer depends on the order the directory reported groups in (a
   * child reported before its parent used to resolve to a permanent {@code null}), and it applies
   * to existing groups too, not only newly created ones (a reorganisation that reassigns an
   * existing unit under a different parent used to be silently ignored).
   */
  private void resolveAndApplyParentLinks(SyncPlan plan, List<Group> createdGroups) {
    Map<String, UUID> groupIdByExternalId = new HashMap<>();
    for (Group group : plan.existingOrgUnits()) {
      if (group.getExternalId() != null) {
        groupIdByExternalId.put(group.getExternalId(), group.getId());
      }
    }
    for (Group created : createdGroups) {
      groupIdByExternalId.put(created.getExternalId(), created.getId());
    }

    List<Group> parentChanged = new ArrayList<>();
    for (Group group : concat(plan.existingOrgUnits(), createdGroups)) {
      DirectoryGroup incoming = plan.incomingByExternalId().get(group.getExternalId());
      if (incoming == null) {
        // Not reported this run (dissolved, or externalId null) - its parent link is left as the
        // last-known-good value, consistent with the rest of the frozen state.
        continue;
      }
      UUID desiredParentId =
          incoming.parentExternalId() == null || incoming.parentExternalId().isBlank()
              ? null
              : groupIdByExternalId.get(incoming.parentExternalId());
      if (!Objects.equals(group.getParentGroupId(), desiredParentId)) {
        group.updateParentGroup(desiredParentId);
        parentChanged.add(group);
      }
    }
    if (!parentChanged.isEmpty()) {
      groupRepository.saveAll(parentChanged);
    }
  }

  private List<Group> concat(List<Group> a, List<Group> b) {
    List<Group> result = new ArrayList<>(a.size() + b.size());
    result.addAll(a);
    result.addAll(b);
    return result;
  }

  // ---------------------------------------------------------------------------------------
  // Report assembly - status is recorded by DirectorySyncService, once this class's own
  // transaction has committed successfully. See the class javadoc.
  // ---------------------------------------------------------------------------------------

  private SyncReport buildReport(
      Instant now, DirectorySyncOutcome outcome, String message, SyncPlan plan) {
    return new SyncReport(
        outcome,
        now,
        toChanges(plan.creates()),
        toRenameChanges(plan.renames()),
        toDissolutionChanges(plan.dissolutions()),
        toMembershipChanges(plan),
        plan.membershipsAdded(),
        plan.membershipsRemoved(),
        plan.unresolvedMemberCount(),
        plan.changedFraction(),
        properties.changeThresholdFraction(),
        message);
  }

  static SyncPlan emptyPlan() {
    return new SyncPlan(
        List.of(), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0, 0, 0, 0, 0,
        0, 0.0);
  }

  private List<GroupChange> toChanges(List<PlannedCreate> creates) {
    List<GroupChange> result = new ArrayList<>();
    for (PlannedCreate create : creates) {
      result.add(
          new GroupChange(
              create.directoryGroup().externalId(), create.directoryGroup().name(), null));
    }
    return result;
  }

  private List<GroupChange> toRenameChanges(List<PlannedRename> renames) {
    List<GroupChange> result = new ArrayList<>();
    for (PlannedRename rename : renames) {
      result.add(
          new GroupChange(
              rename.group().getExternalId(), rename.newName(), rename.group().getName()));
    }
    return result;
  }

  private List<GroupChange> toDissolutionChanges(List<PlannedDissolution> dissolutions) {
    List<GroupChange> result = new ArrayList<>();
    for (PlannedDissolution dissolution : dissolutions) {
      result.add(
          new GroupChange(
              dissolution.group().getExternalId(), dissolution.group().getName(), null));
    }
    return result;
  }

  /**
   * Which users would gain or lose membership of which group - not just the aggregate counts.
   * Review of PR #297: an admin deciding whether to let a run through needs to see who is affected,
   * not only how many. Covers both newly created groups (all members are additions) and existing
   * groups with an actual diff; a matched-but-unchanged group produces no entry.
   */
  private List<MembershipChange> toMembershipChanges(SyncPlan plan) {
    List<MembershipChange> result = new ArrayList<>();
    for (PlannedCreate create : plan.creates()) {
      result.add(
          new MembershipChange(
              create.directoryGroup().externalId(),
              create.directoryGroup().name(),
              toUserRefs(create.members()),
              List.of()));
    }
    for (PlannedMembershipChange change : plan.membershipChanges()) {
      result.add(
          new MembershipChange(
              change.group().getExternalId(),
              change.group().getName(),
              toUserRefs(change.toAdd()),
              toUserRefs(change.toRemove())));
    }
    return result;
  }

  private List<UserRef> toUserRefs(Set<ResolvedUserRef> users) {
    return users.stream().map(u -> new UserRef(u.id(), u.displayName())).toList();
  }

  /**
   * Same deferred-invalidation pattern as {@code GroupService#invalidateAfterCommit}: an inline
   * invalidation here could race a concurrent reader into repopulating the cache with pre-commit
   * membership, for the same reason described there.
   */
  private void invalidateAfterCommit(Runnable invalidation) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      invalidation.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            invalidation.run();
          }
        });
  }

  // ---------------------------------------------------------------------------------------
  // Plan data structures
  // ---------------------------------------------------------------------------------------

  /** A member reference resolved from the directory's subject to a known user's id. */
  private record ResolvedUserRef(UUID id, String displayName) {}

  private record ResolvedMembers(Set<ResolvedUserRef> users, int unresolvedCount) {}

  private record PlannedCreate(DirectoryGroup directoryGroup, Set<ResolvedUserRef> members) {}

  private record PlannedRename(Group group, String newName) {}

  private record PlannedReactivation(Group group) {}

  private record PlannedMembershipChange(
      Group group, Set<ResolvedUserRef> toAdd, Set<ResolvedUserRef> toRemove) {}

  private record PlannedDissolution(Group group) {}

  private record SyncPlan(
      List<Group> existingOrgUnits,
      Map<String, DirectoryGroup> incomingByExternalId,
      List<PlannedCreate> creates,
      List<PlannedRename> renames,
      List<PlannedReactivation> reactivations,
      List<PlannedMembershipChange> membershipChanges,
      List<PlannedDissolution> dissolutions,
      int membershipsAdded,
      int membershipsRemoved,
      int membershipsAtRisk,
      int unresolvedMemberCount,
      int existingMembershipCount,
      int activeGroupCount,
      double changedFraction) {}
}
