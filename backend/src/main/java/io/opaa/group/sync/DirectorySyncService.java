package io.opaa.group.sync;

import io.opaa.api.dto.DirectorySyncGroupChange;
import io.opaa.api.dto.DirectorySyncReportResponse;
import io.opaa.api.dto.DirectorySyncStatusResponse;
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
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Directory synchronisation as a rights event (#237). Groups of {@link GroupKind#ORG_UNIT} exist
 * only because a directory reports them; this service is the one place that reconciles the database
 * with the directory's current state, and does so as a bulk permission change - the only place in
 * the system where rights change in large numbers without an individual human decision.
 *
 * <p><b>Plan, then act.</b> {@link #buildPlan} computes the entire diff against the directory's
 * snapshot without mutating anything. {@link #dryRun} and the plausibility-threshold abort path in
 * {@link #run} both stop right there and only turn the plan into a report - so "nothing was
 * written" is true by construction for those paths, not by carefully avoiding a save() call
 * somewhere. Only {@link #run}, once the plan has been judged plausible, calls {@link #apply}.
 *
 * <p><b>Matching:</b> exclusively by {@link Group#getExternalId()} - the directory's stable
 * identifier, never by name (see #237's acceptance criteria; a rename must be free of side
 * effects).
 *
 * <p><b>Membership resolution:</b> flat only. A group's membership is exactly what the directory
 * reports as its direct members; a member of a child organizational unit is never treated as an
 * implicit member of the parent. Nested-group membership inheritance is called out as an open point
 * in the feature spec and is deliberately left unresolved by this service - {@code parentGroupId}
 * is recorded for #208's curator escalation only.
 */
@Service
@Transactional(readOnly = true)
public class DirectorySyncService {

  private static final Logger log = LoggerFactory.getLogger(DirectorySyncService.class);

  private final DirectoryClient directoryClient;
  private final GroupRepository groupRepository;
  private final UserRepository userRepository;
  private final GroupMembershipResolver membershipResolver;
  private final DirectorySyncStatusRepository statusRepository;
  private final DirectorySyncProperties properties;

  public DirectorySyncService(
      DirectoryClient directoryClient,
      GroupRepository groupRepository,
      UserRepository userRepository,
      GroupMembershipResolver membershipResolver,
      DirectorySyncStatusRepository statusRepository,
      DirectorySyncProperties properties) {
    this.directoryClient = directoryClient;
    this.groupRepository = groupRepository;
    this.userRepository = userRepository;
    this.membershipResolver = membershipResolver;
    this.statusRepository = statusRepository;
    this.properties = properties;
  }

  /** Computes the diff against the directory's current state. Never writes anything. */
  public DirectorySyncReportResponse dryRun(UUID organizationId) {
    return execute(organizationId, false);
  }

  /**
   * Computes the diff and applies it if - and only if - the directory was reachable, did not return
   * an implausibly empty group list, and the fraction of memberships it would remove does not
   * exceed {@link DirectorySyncProperties#changeThresholdFraction()}. Otherwise behaves like {@link
   * #dryRun} and reports why nothing was written.
   */
  @Transactional
  public DirectorySyncReportResponse run(UUID organizationId) {
    return execute(organizationId, true);
  }

  public DirectorySyncStatusResponse getStatus(UUID organizationId) {
    return statusRepository
        .findByOrganizationId(organizationId)
        .map(
            status ->
                new DirectorySyncStatusResponse()
                    .lastRunAt(status.getLastRunAt())
                    .lastOutcome(status.getLastOutcome())
                    .lastMessage(status.getLastMessage())
                    .lastAppliedAt(status.getLastAppliedAt())
                    .lastChangedFraction(status.getLastChangedFraction()))
        .orElseGet(DirectorySyncStatusResponse::new);
  }

  private DirectorySyncReportResponse execute(UUID organizationId, boolean applyIfPlausible) {
    Instant now = Instant.now();
    DirectorySnapshot snapshot;
    try {
      snapshot = directoryClient.fetchGroups(organizationId);
    } catch (DirectoryUnavailableException e) {
      String message = "Verzeichnis nicht erreichbar. Der letzte bekannte Stand bleibt in Kraft.";
      log.warn(
          "Directory sync: directory unreachable for organization {}: {}",
          organizationId,
          e.getMessage());
      return recordAndReport(
          organizationId, now, DirectorySyncOutcome.UNREACHABLE, message, emptyPlan());
    }

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
    double changedFraction =
        plan.existingMembershipCount() == 0
            ? 0.0
            : (double) plan.membershipsRemoved() / plan.existingMembershipCount();

    if (changedFraction > properties.changeThresholdFraction()) {
      String message =
          String.format(
              Locale.ROOT,
              "Der Lauf wuerde %.1f%% der bestehenden Mitgliedschaften entfernen und damit die"
                  + " konfigurierte Schwelle von %.1f%% ueberschreiten. Abgebrochen ohne"
                  + " Aenderungen.",
              changedFraction * 100,
              properties.changeThresholdFraction() * 100);
      log.warn(
          "Directory sync: run for organization {} would remove {} of {} memberships ({}%%,"
              + " threshold {}%%) - aborting without changes",
          organizationId,
          plan.membershipsRemoved(),
          plan.existingMembershipCount(),
          changedFraction * 100,
          properties.changeThresholdFraction() * 100);
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

    int existingMembershipCount = 0;
    for (Group group : existingOrgUnits) {
      if (!group.isDissolved()) {
        existingMembershipCount += group.getMemberships().size();
      }
    }

    List<PlannedCreate> creates = new ArrayList<>();
    List<PlannedRename> renames = new ArrayList<>();
    List<PlannedReactivation> reactivations = new ArrayList<>();
    List<PlannedMembershipChange> membershipChanges = new ArrayList<>();
    int membershipsAdded = 0;
    int membershipsRemoved = 0;
    int unresolvedMemberCount = 0;

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

      ResolvedMembers resolved = resolveMembers(organizationId, incoming.memberSubjects());
      unresolvedMemberCount += resolved.unresolvedCount();

      Group existing = existingByExternalId.get(incoming.externalId());
      if (existing == null) {
        creates.add(new PlannedCreate(incoming, resolved.userIds()));
        membershipsAdded += resolved.userIds().size();
        continue;
      }

      if (existing.isDissolved()) {
        reactivations.add(new PlannedReactivation(existing));
      }

      if (!existing.getName().equals(incoming.name())) {
        renames.add(new PlannedRename(existing, incoming.name()));
      }

      Set<UUID> currentMemberIds = new HashSet<>();
      for (GroupMembership membership : existing.getMemberships()) {
        currentMemberIds.add(membership.getUserId());
      }
      Set<UUID> toAdd = new HashSet<>(resolved.userIds());
      toAdd.removeAll(currentMemberIds);
      Set<UUID> toRemove = new HashSet<>(currentMemberIds);
      toRemove.removeAll(resolved.userIds());

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

    return new SyncPlan(
        creates,
        renames,
        reactivations,
        membershipChanges,
        dissolutions,
        membershipsAdded,
        membershipsRemoved,
        unresolvedMemberCount,
        existingMembershipCount);
  }

  private ResolvedMembers resolveMembers(UUID organizationId, Set<String> subjects) {
    if (subjects.isEmpty()) {
      return new ResolvedMembers(Set.of(), 0);
    }
    List<User> users = userRepository.findByOrganizationIdAndSubjectIn(organizationId, subjects);
    Set<UUID> userIds = new HashSet<>();
    for (User user : users) {
      userIds.add(user.getId());
    }
    int unresolved = subjects.size() - users.size();
    return new ResolvedMembers(userIds, Math.max(unresolved, 0));
  }

  // ---------------------------------------------------------------------------------------
  // Applying the plan
  // ---------------------------------------------------------------------------------------

  private void applyPlan(UUID organizationId, Instant now, SyncPlan plan) {
    Set<UUID> affectedUserIds = new HashSet<>();

    for (PlannedCreate create : plan.creates()) {
      DirectoryGroup incoming = create.directoryGroup();
      UUID parentGroupId = resolveParentGroupId(organizationId, incoming.parentExternalId());
      Group group =
          new Group(
              organizationId,
              GroupKind.ORG_UNIT,
              incoming.name(),
              null,
              incoming.externalId(),
              parentGroupId);
      for (UUID userId : create.memberUserIds()) {
        group.addMembership(new GroupMembership(userId, organizationId));
        affectedUserIds.add(userId);
      }
      groupRepository.save(group);
      log.info(
          "Directory sync: created group {} ({}, external id {}) with {} member(s)",
          group.getId(),
          group.getName(),
          group.getExternalId(),
          create.memberUserIds().size());
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
      for (UUID userId : change.toAdd()) {
        change.group().addMembership(new GroupMembership(userId, organizationId));
        affectedUserIds.add(userId);
        log.info(
            "Directory sync: added user {} to group {} ({})",
            userId,
            change.group().getId(),
            change.group().getExternalId());
      }
      for (UUID userId : change.toRemove()) {
        change.group().getMemberships().stream()
            .filter(m -> m.getUserId().equals(userId))
            .findFirst()
            .ifPresent(change.group()::removeMembership);
        affectedUserIds.add(userId);
        log.info(
            "Directory sync: removed user {} from group {} ({})",
            userId,
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

    if (!affectedUserIds.isEmpty()) {
      invalidateAfterCommit(() -> membershipResolver.invalidateUsers(affectedUserIds));
    }
  }

  private UUID resolveParentGroupId(UUID organizationId, String parentExternalId) {
    if (parentExternalId == null || parentExternalId.isBlank()) {
      return null;
    }
    return groupRepository.findByOrganizationIdAndKindOrgUnit(organizationId).stream()
        .filter(g -> parentExternalId.equals(g.getExternalId()))
        .map(Group::getId)
        .findFirst()
        .orElse(null);
  }

  // ---------------------------------------------------------------------------------------
  // Status persistence and report assembly
  // ---------------------------------------------------------------------------------------

  private DirectorySyncReportResponse recordAndReport(
      UUID organizationId,
      Instant now,
      DirectorySyncOutcome outcome,
      String message,
      SyncPlan plan) {
    double changedFraction =
        plan.existingMembershipCount() == 0
            ? 0.0
            : (double) plan.membershipsRemoved() / plan.existingMembershipCount();

    DirectorySyncStatus status =
        statusRepository
            .findByOrganizationId(organizationId)
            .orElseGet(() -> new DirectorySyncStatus(organizationId));
    status.recordRun(now, outcome, message, changedFraction);
    statusRepository.save(status);

    DirectorySyncReportResponse response =
        new DirectorySyncReportResponse(
            outcome,
            now,
            toChanges(plan.creates()),
            toRenameChanges(plan.renames()),
            toDissolutionChanges(plan.dissolutions()),
            plan.membershipsAdded(),
            plan.membershipsRemoved(),
            changedFraction,
            properties.changeThresholdFraction(),
            message);
    response.unresolvedMemberCount(plan.unresolvedMemberCount());
    return response;
  }

  private SyncPlan emptyPlan() {
    return new SyncPlan(List.of(), List.of(), List.of(), List.of(), List.of(), 0, 0, 0, 0);
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

  private record ResolvedMembers(Set<UUID> userIds, int unresolvedCount) {}

  private record PlannedCreate(DirectoryGroup directoryGroup, Set<UUID> memberUserIds) {}

  private record PlannedRename(Group group, String newName) {}

  private record PlannedReactivation(Group group) {}

  private record PlannedMembershipChange(Group group, Set<UUID> toAdd, Set<UUID> toRemove) {}

  private record PlannedDissolution(Group group) {}

  private record SyncPlan(
      List<PlannedCreate> creates,
      List<PlannedRename> renames,
      List<PlannedReactivation> reactivations,
      List<PlannedMembershipChange> membershipChanges,
      List<PlannedDissolution> dissolutions,
      int membershipsAdded,
      int membershipsRemoved,
      int unresolvedMemberCount,
      int existingMembershipCount) {}
}
