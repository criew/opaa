package io.opaa.group.sync;

import io.opaa.api.dto.DirectorySyncGroupChange;
import io.opaa.api.dto.DirectorySyncMembershipChange;
import io.opaa.api.dto.DirectorySyncReportResponse;
import io.opaa.api.dto.DirectorySyncUserRef;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.Group;
import io.opaa.group.GroupKind;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupMembershipResolver;
import io.opaa.group.GroupRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
 * judged plausible, calls {@link #applyPlan}. Status is always recorded through {@link
 * DirectorySyncStatusRecorder}'s own {@code REQUIRES_NEW} transaction, independent of whichever of
 * the two transaction types below is active - see that class's javadoc for why a plain field write
 * here is not equivalent.
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
 * <p><b>Plausibility threshold ({@link #buildPlan}):</b> the denominator and numerator of {@code
 * changedFraction} both range over exactly the same population of groups - every group this run
 * actually touches (matched-and-unchanged, renamed, membership-changed, reactivated, or about to be
 * dissolved) - and nothing else. A group that was already dissolved before this run and stays
 * unreported keeps its frozen membership out of both sides. Two things review of PR #297 found
 * broken under the original (name-only) computation are folded in explicitly: a group being
 * dissolved this run contributes its entire pre-run membership to the numerator (its reach is
 * capped the same way a revoked grant would be, even though no individual {@code group_memberships}
 * row is deleted), and a group being reactivated this run contributes its frozen pre-run membership
 * to the denominator (it re-enters "in play" in the very run that can remove from it).
 */
@Service
class DirectorySyncPlanExecutor {

  private static final Logger log = LoggerFactory.getLogger(DirectorySyncPlanExecutor.class);

  private final GroupRepository groupRepository;
  private final UserRepository userRepository;
  private final GroupMembershipResolver membershipResolver;
  private final DirectorySyncStatusRecorder statusRecorder;
  private final DirectorySyncProperties properties;

  DirectorySyncPlanExecutor(
      GroupRepository groupRepository,
      UserRepository userRepository,
      GroupMembershipResolver membershipResolver,
      DirectorySyncStatusRecorder statusRecorder,
      DirectorySyncProperties properties) {
    this.groupRepository = groupRepository;
    this.userRepository = userRepository;
    this.membershipResolver = membershipResolver;
    this.statusRecorder = statusRecorder;
    this.properties = properties;
  }

  @Transactional(readOnly = true)
  DirectorySyncReportResponse planOnly(
      UUID organizationId, Instant now, DirectorySnapshot snapshot) {
    return handle(organizationId, now, snapshot, false);
  }

  @Transactional
  DirectorySyncReportResponse planAndApply(
      UUID organizationId, Instant now, DirectorySnapshot snapshot) {
    return handle(organizationId, now, snapshot, true);
  }

  private DirectorySyncReportResponse handle(
      UUID organizationId, Instant now, DirectorySnapshot snapshot, boolean applyIfPlausible) {
    List<Group> existingOrgUnits =
        groupRepository.findByOrganizationIdAndKindOrgUnit(organizationId);

    if (snapshot.groups().isEmpty() && !existingOrgUnits.isEmpty()) {
      String message =
          "Das Verzeichnis hat eine leere Gruppenliste geliefert, obwohl "
              + existingOrgUnits.size()
              + " Organisationseinheit(en) bekannt sind. Der Lauf wurde ohne Aenderungen"
              + " abgebrochen.";
      log.warn(
          "Directory sync: directory returned an empty group list for organization {} while {}"
              + " ORG_UNIT groups exist - aborting without changes",
          organizationId,
          existingOrgUnits.size());
      return recordAndReport(
          organizationId, now, DirectorySyncOutcome.ABORTED_EMPTY_RESULT, message, emptyPlan());
    }

    SyncPlan plan = buildPlan(organizationId, snapshot, existingOrgUnits);

    if (plan.changedFraction() > properties.changeThresholdFraction()) {
      String changedPercent = formatPercent(plan.changedFraction());
      String thresholdPercent = formatPercent(properties.changeThresholdFraction());
      String message =
          String.format(
              Locale.ROOT,
              "Der Lauf wuerde %s der betroffenen Mitgliedschaften entfernen bzw. einfrieren und"
                  + " damit die konfigurierte Schwelle von %s ueberschreiten. Abgebrochen ohne"
                  + " Aenderungen.",
              changedPercent,
              thresholdPercent);
      log.warn(
          "Directory sync: run for organization {} would put {} of {} memberships at risk ({},"
              + " threshold {}) - aborting without changes",
          organizationId,
          plan.membershipsAtRisk(),
          plan.existingMembershipCount(),
          changedPercent,
          thresholdPercent);
      return recordAndReport(
          organizationId, now, DirectorySyncOutcome.ABORTED_THRESHOLD, message, plan);
    }

    if (!applyIfPlausible) {
      return recordAndReport(
          organizationId,
          now,
          DirectorySyncOutcome.DRY_RUN,
          "Trockenlauf - keine Aenderung.",
          plan);
    }

    applyPlan(organizationId, now, plan);
    return recordAndReport(
        organizationId, now, DirectorySyncOutcome.APPLIED, "Synchronisation angewendet.", plan);
  }

  private String formatPercent(double fraction) {
    return String.format(Locale.ROOT, "%.1f%%", fraction * 100);
  }

  // ---------------------------------------------------------------------------------------
  // Plan building - read-only
  // ---------------------------------------------------------------------------------------

  private SyncPlan buildPlan(
      UUID organizationId, DirectorySnapshot snapshot, List<Group> existingOrgUnits) {
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

      ResolvedMembers resolved = resolveMembers(organizationId, incoming.memberSubjects());
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

      Map<UUID, UserRef> currentMembers = new HashMap<>();
      for (GroupMembership membership : existing.getMemberships()) {
        currentMembers.put(membership.getUserId(), new UserRef(membership.getUserId(), null));
      }
      Map<UUID, UserRef> desiredMembers = new HashMap<>();
      for (UserRef user : resolved.users()) {
        desiredMembers.put(user.id(), user);
      }

      Set<UserRef> toAdd = new HashSet<>();
      for (Map.Entry<UUID, UserRef> entry : desiredMembers.entrySet()) {
        if (!currentMembers.containsKey(entry.getKey())) {
          toAdd.add(entry.getValue());
        }
      }
      Set<UserRef> toRemove = new HashSet<>();
      for (Map.Entry<UUID, UserRef> entry : currentMembers.entrySet()) {
        if (!desiredMembers.containsKey(entry.getKey())) {
          toRemove.add(entry.getValue());
        }
      }

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
    for (Group group : existingOrgUnits) {
      if (!group.isDissolved() || reactivatingGroupIds.contains(group.getId())) {
        existingMembershipCount += group.getMemberships().size();
      }
    }
    int membershipsAtRisk = membershipsRemoved;
    for (PlannedDissolution dissolution : dissolutions) {
      membershipsAtRisk += dissolution.group().getMemberships().size();
    }
    double changedFraction =
        existingMembershipCount == 0
            ? (membershipsAtRisk > 0 ? 1.0 : 0.0)
            : (double) membershipsAtRisk / existingMembershipCount;

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
        changedFraction);
  }

  private ResolvedMembers resolveMembers(UUID organizationId, Set<String> subjects) {
    if (subjects.isEmpty()) {
      return new ResolvedMembers(Set.of(), 0);
    }
    List<User> users = userRepository.findByOrganizationIdAndSubjectIn(organizationId, subjects);
    Set<UserRef> userRefs = new HashSet<>();
    for (User user : users) {
      String displayName = user.getDisplayName() != null ? user.getDisplayName() : user.getEmail();
      userRefs.add(new UserRef(user.getId(), displayName));
    }
    int unresolved = subjects.size() - users.size();
    return new ResolvedMembers(userRefs, Math.max(unresolved, 0));
  }

  // ---------------------------------------------------------------------------------------
  // Applying the plan
  // ---------------------------------------------------------------------------------------

  private void applyPlan(UUID organizationId, Instant now, SyncPlan plan) {
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
      for (UserRef member : create.members()) {
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
    }

    for (PlannedRename rename : plan.renames()) {
      log.info(
          "Directory sync: group {} ({}) renamed from '{}' to '{}'",
          rename.group().getId(),
          rename.group().getExternalId(),
          rename.group().getName(),
          rename.newName());
      rename.group().renameFromDirectory(rename.newName());
    }

    for (PlannedMembershipChange change : plan.membershipChanges()) {
      for (UserRef member : change.toAdd()) {
        change.group().addMembership(new GroupMembership(member.id(), organizationId));
        affectedUserIds.add(member.id());
        log.info(
            "Directory sync: added user {} to group {} ({})",
            member.id(),
            change.group().getId(),
            change.group().getExternalId());
      }
      for (UserRef member : change.toRemove()) {
        change.group().getMemberships().stream()
            .filter(m -> m.getUserId().equals(member.id()))
            .findFirst()
            .ifPresent(change.group()::removeMembership);
        affectedUserIds.add(member.id());
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
  // Status recording and report assembly
  // ---------------------------------------------------------------------------------------

  private DirectorySyncReportResponse recordAndReport(
      UUID organizationId,
      Instant now,
      DirectorySyncOutcome outcome,
      String message,
      SyncPlan plan) {
    statusRecorder.record(organizationId, now, outcome, message, plan.changedFraction());

    DirectorySyncReportResponse response =
        new DirectorySyncReportResponse(
            outcome,
            now,
            toChanges(plan.creates()),
            toRenameChanges(plan.renames()),
            toDissolutionChanges(plan.dissolutions()),
            toMembershipChanges(plan),
            plan.membershipsAdded(),
            plan.membershipsRemoved(),
            plan.changedFraction(),
            properties.changeThresholdFraction(),
            message);
    response.unresolvedMemberCount(plan.unresolvedMemberCount());
    return response;
  }

  static SyncPlan emptyPlan() {
    return new SyncPlan(
        List.of(), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0, 0, 0, 0, 0,
        0.0);
  }

  private List<DirectorySyncGroupChange> toChanges(List<PlannedCreate> creates) {
    List<DirectorySyncGroupChange> result = new ArrayList<>();
    for (PlannedCreate create : creates) {
      result.add(
          new DirectorySyncGroupChange(
              create.directoryGroup().externalId(), create.directoryGroup().name()));
    }
    return result;
  }

  private List<DirectorySyncGroupChange> toRenameChanges(List<PlannedRename> renames) {
    List<DirectorySyncGroupChange> result = new ArrayList<>();
    for (PlannedRename rename : renames) {
      result.add(
          new DirectorySyncGroupChange(rename.group().getExternalId(), rename.newName())
              .previousName(rename.group().getName()));
    }
    return result;
  }

  private List<DirectorySyncGroupChange> toDissolutionChanges(
      List<PlannedDissolution> dissolutions) {
    List<DirectorySyncGroupChange> result = new ArrayList<>();
    for (PlannedDissolution dissolution : dissolutions) {
      result.add(
          new DirectorySyncGroupChange(
              dissolution.group().getExternalId(), dissolution.group().getName()));
    }
    return result;
  }

  /**
   * Which users would gain or lose membership of which group - not just the aggregate counts.
   * Review of PR #297: an admin deciding whether to let a run through needs to see who is affected,
   * not only how many. Covers both newly created groups (all members are additions) and existing
   * groups with an actual diff; a matched-but-unchanged group produces no entry.
   */
  private List<DirectorySyncMembershipChange> toMembershipChanges(SyncPlan plan) {
    List<DirectorySyncMembershipChange> result = new ArrayList<>();
    for (PlannedCreate create : plan.creates()) {
      result.add(
          new DirectorySyncMembershipChange(
              create.directoryGroup().externalId(),
              create.directoryGroup().name(),
              toUserRefs(create.members()),
              List.of()));
    }
    for (PlannedMembershipChange change : plan.membershipChanges()) {
      result.add(
          new DirectorySyncMembershipChange(
              change.group().getExternalId(),
              change.group().getName(),
              toUserRefs(change.toAdd()),
              toUserRefs(change.toRemove())));
    }
    return result;
  }

  private List<DirectorySyncUserRef> toUserRefs(Set<UserRef> users) {
    List<DirectorySyncUserRef> result = new ArrayList<>();
    for (UserRef user : users) {
      result.add(new DirectorySyncUserRef(user.id()).displayName(user.displayName()));
    }
    return result;
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
  private record UserRef(UUID id, String displayName) {}

  private record ResolvedMembers(Set<UserRef> users, int unresolvedCount) {}

  private record PlannedCreate(DirectoryGroup directoryGroup, Set<UserRef> members) {}

  private record PlannedRename(Group group, String newName) {}

  private record PlannedReactivation(Group group) {}

  private record PlannedMembershipChange(Group group, Set<UserRef> toAdd, Set<UserRef> toRemove) {}

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
      double changedFraction) {}
}
