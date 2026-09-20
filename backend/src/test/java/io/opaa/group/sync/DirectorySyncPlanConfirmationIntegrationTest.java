package io.opaa.group.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.DirectorySyncOutcome;
import io.opaa.api.types.GroupKind;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.group.Group;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupMembershipRepository;
import io.opaa.group.GroupRepository;
import io.opaa.organization.Organization;
import io.opaa.permission.GroupMembershipHistoryRepository;
import io.opaa.test.FakeDirectoryClient;
import io.opaa.test.OpaaIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The confirmation path of a run above the plausibility threshold (#1816, ADR-0036 Entscheidung 3),
 * with all four decisions of the plan's lifecycle: the empty-result guard stays a hard abort with
 * no plan, a new run replaces the pending plan, a confirmation is computed against a fresh snapshot
 * and re-presented when it differs, and the plan is a visible state with an age.
 */
@OpaaIntegrationTest
class DirectorySyncPlanConfirmationIntegrationTest {

  @Autowired private DirectorySyncService directorySyncService;
  @Autowired private OidcProviderRepository providerRepository;
  @Autowired private GroupRepository groupRepository;
  @Autowired private GroupMembershipRepository membershipRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private DirectorySyncStatusRepository statusRepository;
  @Autowired private DirectorySyncPendingPlanRepository pendingPlanRepository;
  @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
  @Autowired private FakeDirectoryClient directoryClient;

  private static final UUID ORGANIZATION_ID = Organization.DEFAULT_ID;

  private final List<UUID> createdUserIds = new ArrayList<>();
  private final List<UUID> createdGroupIds = new ArrayList<>();
  private OidcProvider provider;

  @BeforeEach
  void setUp() {
    cleanUp();
    provider =
        new OidcProvider(
            "Verzeichnis " + UUID.randomUUID(),
            "https://idp.example/realms/" + UUID.randomUUID(),
            "opaa-frontend",
            null,
            OidcClaimMapping.keycloakDefaults());
    provider.configureDirectorySync(true, 360);
    providerRepository.save(provider);
    directoryClient.respondWith();
  }

  @AfterEach
  void tearDown() {
    cleanUp();
    if (provider != null) {
      providerRepository.deleteById(provider.getId());
      provider = null;
    }
  }

  private void cleanUp() {
    if (provider != null) {
      pendingPlanRepository
          .findByOrganizationIdAndProviderId(ORGANIZATION_ID, provider.getId())
          .ifPresent(pendingPlanRepository::delete);
      statusRepository
          .findByOrganizationIdAndProviderId(ORGANIZATION_ID, provider.getId())
          .ifPresent(statusRepository::delete);
    }
    List<Group> ownGroups =
        groupRepository.findByOrganizationId(ORGANIZATION_ID).stream()
            .filter(group -> createdGroupIds.contains(group.getId()))
            .toList();
    membershipRepository.deleteAll(
        ownGroups.stream()
            .flatMap(group -> membershipRepository.findByGroupId(group.getId()).stream())
            .toList());
    groupRepository.deleteAll(ownGroups);
    membershipHistoryRepository.deleteByUserIdIn(List.copyOf(createdUserIds));
    userRepository.deleteAllById(createdUserIds);
    createdUserIds.clear();
    createdGroupIds.clear();
  }

  // ---------------------------------------------------------------------------------------
  // A run above the threshold leaves a plan
  // ---------------------------------------------------------------------------------------

  @Test
  void aRunAboveTheThresholdChangesNothingUntilItIsConfirmed() {
    Group group = groupLosingMostOfItsMembers();

    SyncReport report = directorySyncService.run(ORGANIZATION_ID, provider.getId());

    assertThat(report.outcome()).isEqualTo(DirectorySyncOutcome.PENDING_CONFIRMATION);
    assertThat(membershipRepository.findByGroupId(group.getId())).hasSize(3);
    PendingPlanView plan =
        directorySyncService.getPendingPlan(ORGANIZATION_ID, provider.getId()).orElseThrow();
    // A pending plan is a loud state: its age is on the status line, not only on a subpage.
    assertThat(plan.createdAt()).isNotNull();
    assertThat(plan.membershipsRemoved()).isEqualTo(2);
    assertThat(
            directorySyncService.listStatus(ORGANIZATION_ID).stream()
                .filter(view -> view.providerId().equals(provider.getId()))
                .findFirst()
                .orElseThrow()
                .pendingPlan())
        .isNotNull();
  }

  /** A dry run writes nothing at all - including no plan someone could confirm later. */
  @Test
  void aDryRunAboveTheThresholdLeavesNoPlanAtAll() {
    groupLosingMostOfItsMembers();

    SyncReport report = directorySyncService.dryRun(ORGANIZATION_ID, provider.getId());

    assertThat(report.outcome()).isEqualTo(DirectorySyncOutcome.ABORTED_THRESHOLD);
    assertThat(directorySyncService.getPendingPlan(ORGANIZATION_ID, provider.getId())).isEmpty();
  }

  /**
   * ADR-0036, Entscheidung 3, first decision: "die Quelle hat nicht geantwortet, wie sie soll" is
   * nothing anyone may click away.
   */
  @Test
  void anEmptyGroupListStaysAHardAbortAndProducesNoConfirmablePlan() {
    Group group = groupLosingMostOfItsMembers();
    directoryClient.respondWithFor(provider.getId());

    SyncReport report = directorySyncService.run(ORGANIZATION_ID, provider.getId());

    assertThat(report.outcome()).isEqualTo(DirectorySyncOutcome.ABORTED_EMPTY_RESULT);
    assertThat(directorySyncService.getPendingPlan(ORGANIZATION_ID, provider.getId())).isEmpty();
    assertThat(membershipRepository.findByGroupId(group.getId())).hasSize(3);
  }

  /** Second decision: otherwise one plan would pile up every six hours. */
  @Test
  void aSecondRunAboveTheThresholdReplacesThePendingPlan() {
    groupLosingMostOfItsMembers();
    directorySyncService.run(ORGANIZATION_ID, provider.getId());
    UUID firstPlanId =
        directorySyncService.getPendingPlan(ORGANIZATION_ID, provider.getId()).orElseThrow().id();

    directoryClient.respondWithFor(
        provider.getId(), new DirectoryGroup("dir-1", "Referat 50", null, Set.of()));
    directorySyncService.run(ORGANIZATION_ID, provider.getId());

    PendingPlanView current =
        directorySyncService.getPendingPlan(ORGANIZATION_ID, provider.getId()).orElseThrow();
    assertThat(current.id()).isNotEqualTo(firstPlanId);
    assertThat(pendingPlanRepository.findByOrganizationId(ORGANIZATION_ID))
        .filteredOn(plan -> plan.getProviderId().equals(provider.getId()))
        .hasSize(1);
    // The plan that was replaced is gone, not merely hidden.
    assertThatThrownBy(
            () ->
                directorySyncService.confirmPlan(
                    ORGANIZATION_ID, provider.getId(), firstPlanId, null, "Anlass"))
        .isInstanceOf(NotFoundException.class);
  }

  // ---------------------------------------------------------------------------------------
  // Confirming and discarding
  // ---------------------------------------------------------------------------------------

  @Test
  void aConfirmedPlanIsAppliedAndTheHeaderEntryNamesThePersonAndTheirReason() {
    Group group = groupLosingMostOfItsMembers();
    UUID actor = createUser("actor");
    directorySyncService.run(ORGANIZATION_ID, provider.getId());
    UUID planId =
        directorySyncService.getPendingPlan(ORGANIZATION_ID, provider.getId()).orElseThrow().id();

    SyncReport report =
        directorySyncService.confirmPlan(
            ORGANIZATION_ID, provider.getId(), planId, actor, "Reorganisation zum 01.10.");

    assertThat(report.outcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
    assertThat(membershipRepository.findByGroupId(group.getId())).hasSize(1);
    assertThat(directorySyncService.getPendingPlan(ORGANIZATION_ID, provider.getId())).isEmpty();
    // "oberhalb der Schwelle, mit der bestaetigenden Person und ihrem Anlass"
    // (security-and-compliance.md): the header entry of a confirmed run names both.
    assertThat(
            countAuditEntries(
                AuditEventType.DIRECTORY_SYNC_RUN_COMPLETED, "%Reorganisation zum 01.10.%"))
        .isEqualTo(1);
  }

  /** Third decision: confirmed against a fresh snapshot, re-presented when it differs. */
  @Test
  void aPlanWhoseDiffChangedSinceItWasShownIsRePresentedInsteadOfApplied() {
    Group group = groupLosingMostOfItsMembers();
    directorySyncService.run(ORGANIZATION_ID, provider.getId());
    UUID planId =
        directorySyncService.getPendingPlan(ORGANIZATION_ID, provider.getId()).orElseThrow().id();

    // The directory has moved on: now the group loses all three instead of two.
    directoryClient.respondWithFor(
        provider.getId(), new DirectoryGroup("dir-1", "Referat 50", null, Set.of()));

    Throwable thrown =
        catchThrowable(
            () ->
                directorySyncService.confirmPlan(
                    ORGANIZATION_ID, provider.getId(), planId, null, "Anlass"));

    assertThat(thrown).isInstanceOf(ConflictException.class);
    assertThat(((ConflictException) thrown).getCode())
        .isEqualTo(DirectorySyncService.PLAN_CHANGED_CODE);
    assertThat(membershipRepository.findByGroupId(group.getId())).hasSize(3);
    PendingPlanView replacement =
        directorySyncService.getPendingPlan(ORGANIZATION_ID, provider.getId()).orElseThrow();
    assertThat(replacement.id()).isNotEqualTo(planId);
    assertThat(replacement.membershipsRemoved()).isEqualTo(3);
  }

  @Test
  void aDiscardedPlanLeavesTheMembershipsUntouchedAndIsAuditedWithItsReason() {
    Group group = groupLosingMostOfItsMembers();
    UUID actor = createUser("actor");
    directorySyncService.run(ORGANIZATION_ID, provider.getId());
    UUID planId =
        directorySyncService.getPendingPlan(ORGANIZATION_ID, provider.getId()).orElseThrow().id();

    directorySyncService.discardPlan(
        ORGANIZATION_ID, provider.getId(), planId, actor, "Verzeichnis war fehlerhaft.");

    assertThat(directorySyncService.getPendingPlan(ORGANIZATION_ID, provider.getId())).isEmpty();
    assertThat(membershipRepository.findByGroupId(group.getId())).hasSize(3);
    assertThat(
            countAuditEntries(
                AuditEventType.DIRECTORY_SYNC_PLAN_DISCARDED, "Verzeichnis war fehlerhaft."))
        .isEqualTo(1);
  }

  @Test
  void anUnknownPlanIdIsRefusedForBothDecisions() {
    assertThatThrownBy(
            () ->
                directorySyncService.confirmPlan(
                    ORGANIZATION_ID, provider.getId(), UUID.randomUUID(), null, "Anlass"))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(
            () ->
                directorySyncService.discardPlan(
                    ORGANIZATION_ID, provider.getId(), UUID.randomUUID(), null, "Anlass"))
        .isInstanceOf(NotFoundException.class);
  }

  // ---------------------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------------------

  /** One group of three members, of which the directory only reports one - 67%, above the 30%. */
  private Group groupLosingMostOfItsMembers() {
    UUID keep = createUser("keep");
    UUID lostA = createUser("lost-a");
    UUID lostB = createUser("lost-b");
    Group group =
        new Group(
            ORGANIZATION_ID,
            GroupKind.ORG_UNIT,
            "Referat 50",
            null,
            provider.getId(),
            "dir-1",
            null,
            null);
    group.addMembership(new GroupMembership(keep, ORGANIZATION_ID));
    group.addMembership(new GroupMembership(lostA, ORGANIZATION_ID));
    group.addMembership(new GroupMembership(lostB, ORGANIZATION_ID));
    Group saved = groupRepository.save(group);
    createdGroupIds.add(saved.getId());
    directoryClient.respondWithFor(
        provider.getId(), new DirectoryGroup("dir-1", "Referat 50", null, Set.of("keep")));
    return saved;
  }

  /** The issuer is this method's own, so a plain subject cannot collide with another method's. */
  private UUID createUser(String subject) {
    User user = new User(subject, provider.getIssuerUri(), subject + "@example.com", "Test User");
    user.setOrganizationId(ORGANIZATION_ID);
    UUID id = userRepository.save(user).getId();
    createdUserIds.add(id);
    return id;
  }

  /**
   * Read through JDBC rather than a repository: {@code AuditLogRepository} is package-private, and
   * the audit log is insert-only at the application layer anyway.
   */
  private int countAuditEntries(AuditEventType eventType, String reasonPattern) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_log WHERE event_type = ? AND reason LIKE ?",
            Integer.class,
            eventType.name(),
            reasonPattern);
    return count == null ? 0 : count;
  }
}
