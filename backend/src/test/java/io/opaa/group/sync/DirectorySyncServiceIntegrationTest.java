package io.opaa.group.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.dto.DirectorySyncMembershipChange;
import io.opaa.api.dto.DirectorySyncReportResponse;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.Group;
import io.opaa.group.GroupKind;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupMembershipHistoryRepository;
import io.opaa.group.GroupMembershipRepository;
import io.opaa.group.GroupRepository;
import io.opaa.organization.Organization;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Exercises {@link DirectorySyncService} against a real Postgres database with the real, versioned
 * Liquibase schema applied ({@code spring.liquibase.enabled=true}, {@code ddl-auto=none}) - not
 * Hibernate-generated DDL, the same pattern {@code GroupServiceIntegrationTest} (#308) and {@code
 * UserServicePersonalSpaceIntegrationTest} also follow: this class's dissolution/reactivation
 * scenarios and the {@code fk_group_memberships_group_organization} composite foreign key both
 * depend on real constraints that only the versioned changelog creates.
 *
 * <p>{@link FakeDirectoryClient} replaces the production {@link NoOpDirectoryClient} as the {@link
 * DirectoryClient} bean (marked {@code @Primary}, so it wins regardless of bean registration order)
 * - the one seam between the synchronisation policy under test and an actual directory, per {@link
 * DirectoryClient}'s own javadoc.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(DirectorySyncServiceIntegrationTest.TestConfig.class)
@ActiveProfiles({"local", "dev"})
@Testcontainers(disabledWithoutDocker = true)
class DirectorySyncServiceIntegrationTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class TestConfig {
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
      return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"));
    }

    @Bean
    @Primary
    FakeDirectoryClient fakeDirectoryClient() {
      return new FakeDirectoryClient();
    }
  }

  static class FakeDirectoryClient implements DirectoryClient {
    private DirectorySnapshot snapshot = new DirectorySnapshot(Instant.now(), List.of());
    private DirectoryUnavailableException failure;

    void respondWith(DirectoryGroup... groups) {
      this.failure = null;
      this.snapshot = new DirectorySnapshot(Instant.now(), List.of(groups));
    }

    void failWith(String message) {
      this.failure = new DirectoryUnavailableException(message);
    }

    @Override
    public DirectorySnapshot fetchGroups(UUID organizationId) throws DirectoryUnavailableException {
      if (failure != null) {
        throw failure;
      }
      return snapshot;
    }
  }

  @Autowired private DirectorySyncService directorySyncService;
  @Autowired private GroupRepository groupRepository;
  @Autowired private GroupMembershipRepository membershipRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private DirectorySyncStatusRepository statusRepository;
  @Autowired private FakeDirectoryClient directoryClient;

  // Real Liquibase FKs are in force in this test (fk_users_organization,
  // fk_directory_sync_status_organization) - a random UUID would violate them. Organization.
  // DEFAULT_ID is the row the 008 changelog seeds, the same one production uses until
  // multi-organization management exists.
  private UUID organizationId;

  @BeforeEach
  void cleanUp() {
    statusRepository.deleteAll();
    // #238 code review, finding 2+4: a sync run now historises every membership change it applies,
    // and group_membership_history.user_id is ON DELETE RESTRICT (see
    // 018-permission-history.yaml's "Deletion survival" comment) - the blanket
    // userRepository.deleteAll() below would otherwise fail from the second test method onward.
    membershipHistoryRepository.deleteAll();
    membershipRepository.deleteAll();
    groupRepository.deleteAll();
    userRepository.deleteAll();
    organizationId = Organization.DEFAULT_ID;
    directoryClient.respondWith();
  }

  private UUID createUser(UUID organizationId, String subject) {
    User user = new User(subject, "test-issuer", subject + "@example.com", "Test User");
    user.setOrganizationId(organizationId);
    return userRepository.save(user).getId();
  }

  private Group persistOrgUnit(String externalId, String name, UUID... memberIds) {
    Group group = new Group(organizationId, GroupKind.ORG_UNIT, name, null, externalId, null);
    for (UUID memberId : memberIds) {
      group.addMembership(new GroupMembership(memberId, organizationId));
    }
    return groupRepository.save(group);
  }

  // ---------------------------------------------------------------------------------------
  // Rename
  // ---------------------------------------------------------------------------------------

  @Test
  void aRenamedGroupKeepsItsGrantsAndItsMembers() {
    UUID member = createUser(organizationId, "member-1");
    Group existing = persistOrgUnit("dir-guid-1", "Altes Referat", member);

    directoryClient.respondWith(
        new DirectoryGroup("dir-guid-1", "Neues Referat", null, Set.of("member-1")));

    DirectorySyncReportResponse report = directorySyncService.run(organizationId);

    assertThat(report.getOutcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
    assertThat(report.getGroupsRenamed()).hasSize(1);
    Group reloaded = groupRepository.findById(existing.getId()).orElseThrow();
    assertThat(reloaded.getName()).isEqualTo("Neues Referat");
    assertThat(reloaded.getExternalId()).isEqualTo("dir-guid-1");
    assertThat(membershipRepository.findByGroupId(reloaded.getId()))
        .extracting(GroupMembership::getUserId)
        .containsExactly(member);
  }

  // ---------------------------------------------------------------------------------------
  // Plausibility threshold
  // ---------------------------------------------------------------------------------------

  @Test
  void aRunThatRemovesMoreThanTheThresholdIsAbortedWithoutAnyChange() {
    UUID memberA = createUser(organizationId, "a");
    UUID memberB = createUser(organizationId, "b");
    UUID memberC = createUser(organizationId, "c");
    Group existing = persistOrgUnit("dir-guid-1", "Referat 50", memberA, memberB, memberC);

    // Directory now reports only "a" - removing 2 of 3 memberships (67%), well above the 30%
    // default threshold.
    directoryClient.respondWith(new DirectoryGroup("dir-guid-1", "Referat 50", null, Set.of("a")));

    DirectorySyncReportResponse report = directorySyncService.run(organizationId);

    assertThat(report.getOutcome()).isEqualTo(DirectorySyncOutcome.ABORTED_THRESHOLD);
    assertThat(membershipRepository.findByGroupId(existing.getId())).hasSize(3);
  }

  // ---------------------------------------------------------------------------------------
  // Empty group list
  // ---------------------------------------------------------------------------------------

  @Test
  void anEmptyGroupListFromTheDirectoryRevokesNoRights() {
    UUID member = createUser(organizationId, "member-1");
    Group existing = persistOrgUnit("dir-guid-1", "Referat 50", member);

    directoryClient.respondWith(); // empty group list

    DirectorySyncReportResponse report = directorySyncService.run(organizationId);

    assertThat(report.getOutcome()).isEqualTo(DirectorySyncOutcome.ABORTED_EMPTY_RESULT);
    assertThat(membershipRepository.findByGroupId(existing.getId())).hasSize(1);
    assertThat(groupRepository.findById(existing.getId()).orElseThrow().isDissolved()).isFalse();
  }

  // ---------------------------------------------------------------------------------------
  // Unreachable directory
  // ---------------------------------------------------------------------------------------

  @Test
  void anUnreachableDirectoryLeavesTheLastKnownGoodStateInForce() {
    UUID member = createUser(organizationId, "member-1");
    Group existing = persistOrgUnit("dir-guid-1", "Referat 50", member);

    directoryClient.failWith("connection refused");

    DirectorySyncReportResponse report = directorySyncService.run(organizationId);

    assertThat(report.getOutcome()).isEqualTo(DirectorySyncOutcome.UNREACHABLE);
    assertThat(membershipRepository.findByGroupId(existing.getId())).hasSize(1);
  }

  // ---------------------------------------------------------------------------------------
  // Dry run
  // ---------------------------------------------------------------------------------------

  @Test
  void dryRunComputesTheFullDiffAndChangesNothing() {
    UUID memberA = createUser(organizationId, "a");
    UUID memberB = createUser(organizationId, "b");
    Group existing = persistOrgUnit("dir-guid-1", "Referat 50", memberA);

    directoryClient.respondWith(
        new DirectoryGroup("dir-guid-1", "Referat 50", null, Set.of("a", "b")),
        new DirectoryGroup("dir-guid-2", "Referat 60", null, Set.of()));

    DirectorySyncReportResponse report = directorySyncService.dryRun(organizationId);

    assertThat(report.getOutcome()).isEqualTo(DirectorySyncOutcome.DRY_RUN);
    assertThat(report.getGroupsCreated()).hasSize(1);
    assertThat(report.getMembershipsAdded()).isEqualTo(1);
    // Nothing persisted.
    assertThat(membershipRepository.findByGroupId(existing.getId())).hasSize(1);
    assertThat(groupRepository.findByOrganizationId(organizationId)).hasSize(1);
  }

  // ---------------------------------------------------------------------------------------
  // Group creation
  // ---------------------------------------------------------------------------------------

  @Test
  void createsANewOrgUnitGroupWithItsMembers() {
    UUID member = createUser(organizationId, "new-member");
    directoryClient.respondWith(
        new DirectoryGroup("dir-guid-9", "Referat 99", null, Set.of("new-member")));

    DirectorySyncReportResponse report = directorySyncService.run(organizationId);

    assertThat(report.getOutcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
    List<Group> groups = groupRepository.findByOrganizationId(organizationId);
    assertThat(groups).hasSize(1);
    Group created = groups.get(0);
    assertThat(created.getExternalId()).isEqualTo("dir-guid-9");
    assertThat(created.getKind()).isEqualTo(GroupKind.ORG_UNIT);
    assertThat(membershipRepository.findByGroupId(created.getId()))
        .extracting(GroupMembership::getUserId)
        .containsExactly(member);
  }

  // ---------------------------------------------------------------------------------------
  // Dissolution and reactivation
  // ---------------------------------------------------------------------------------------

  @Test
  void aGroupNoLongerReportedByTheDirectoryIsMarkedDissolvedWithMembershipFrozen() {
    UUID member = createUser(organizationId, "member-1");
    Group existing = persistOrgUnit("dir-guid-1", "Referat 50", member);
    // A large, unrelated, unaffected group so the one dissolving membership stays well under the
    // 30% membership threshold - this test is about the dissolution mechanics, not the threshold.
    // Only 2 groups are active in total, so the group-count measure (see
    // MIN_ACTIVE_GROUPS_FOR_GROUP_MEASURE) does not apply here - it is exercised separately by
    // aMassDissolutionOfMemberlessGroupsIsCaughtByTheGroupCountMeasureEvenAtZeroMembershipRisk and
    // its counterpart aLegitimateSingleDissolutionInASmallOrganizationPassesThroughUnblocked.
    UUID[] bulkMembers = new UUID[9];
    for (int i = 0; i < bulkMembers.length; i++) {
      bulkMembers[i] = createUser(organizationId, "bulk-" + i);
    }
    persistOrgUnit("dir-guid-bulk", "Referat Bulk", bulkMembers);

    // Directory no longer reports dir-guid-1 at all (merged into another unit); the bulk group is
    // still reported unchanged.
    directoryClient.respondWith(
        new DirectoryGroup("dir-guid-2", "Referat 60", null, Set.of()),
        new DirectoryGroup(
            "dir-guid-bulk",
            "Referat Bulk",
            null,
            Set.of(
                "bulk-0", "bulk-1", "bulk-2", "bulk-3", "bulk-4", "bulk-5", "bulk-6", "bulk-7",
                "bulk-8")));

    DirectorySyncReportResponse report = directorySyncService.run(organizationId);

    assertThat(report.getOutcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
    assertThat(report.getGroupsDissolved()).hasSize(1);
    Group reloaded = groupRepository.findById(existing.getId()).orElseThrow();
    assertThat(reloaded.isDissolved()).isTrue();
    assertThat(reloaded.getDissolvedAt()).isNotNull();
    // Frozen, not revoked - the existing member keeps working.
    assertThat(membershipRepository.findByGroupId(reloaded.getId()))
        .extracting(GroupMembership::getUserId)
        .containsExactly(member);
  }

  @Test
  void aDissolvedGroupThatReappearsInTheDirectoryIsReactivatedAndResynchronised() {
    UUID member = createUser(organizationId, "member-1");
    UUID newMember = createUser(organizationId, "member-2");
    Group existing = persistOrgUnit("dir-guid-1", "Referat 50", member);
    existing.dissolve(Instant.now());
    groupRepository.save(existing);

    directoryClient.respondWith(
        new DirectoryGroup("dir-guid-1", "Referat 50", null, Set.of("member-1", "member-2")));

    DirectorySyncReportResponse report = directorySyncService.run(organizationId);

    assertThat(report.getOutcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
    Group reloaded = groupRepository.findById(existing.getId()).orElseThrow();
    assertThat(reloaded.isDissolved()).isFalse();
    assertThat(reloaded.getDissolvedAt()).isNull();
    assertThat(membershipRepository.findByGroupId(reloaded.getId()))
        .extracting(GroupMembership::getUserId)
        .containsExactlyInAnyOrder(member, newMember);
  }

  // ---------------------------------------------------------------------------------------
  // Combined mechanisms - review of PR #297: the plausibility threshold must catch a partial
  // outage that manifests as mass dissolution, and must not lose its denominator when the
  // affected group was already dissolved and is reactivating in the same run that changes it.
  // ---------------------------------------------------------------------------------------

  @Test
  void aPartialOutageThatDissolvesMostGroupsIsCaughtByThePlausibilityThreshold() {
    // 10 org units, one member each. The directory - after a partial outage - only reports 2 of
    // them; the other 8 would be marked dissolved. Before the fix, a dissolution contributed
    // nothing to the numerator, so this run reported changedFraction = 0.0 and applied silently.
    List<DirectoryGroup> stillReported = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      UUID member = createUser(organizationId, "unit-" + i);
      persistOrgUnit("dir-guid-" + i, "Referat " + i, member);
      if (i < 2) {
        stillReported.add(
            new DirectoryGroup("dir-guid-" + i, "Referat " + i, null, Set.of("unit-" + i)));
      }
    }
    directoryClient.respondWith(stillReported.toArray(new DirectoryGroup[0]));

    DirectorySyncReportResponse report = directorySyncService.run(organizationId);

    assertThat(report.getOutcome()).isEqualTo(DirectorySyncOutcome.ABORTED_THRESHOLD);
    assertThat(report.getChangedFraction()).isEqualTo(0.8);
    long dissolvedCount =
        groupRepository.findByOrganizationId(organizationId).stream()
            .filter(Group::isDissolved)
            .count();
    assertThat(dissolvedCount).isZero();
  }

  @Test
  void reactivatingAnAllDissolvedGroupWithMostOfItsFrozenMembershipMissingIsCaughtByTheThreshold() {
    // Only one group exists in the organization, already dissolved from an earlier run, with a
    // frozen membership of 4. Before the fix, existingMembershipCount excluded every dissolved
    // group unconditionally, so the denominator was 0 and the run "passed" with changedFraction
    // = 0.0 regardless of how much of the reactivated group's membership disappeared.
    UUID keptMember = createUser(organizationId, "kept");
    UUID lostA = createUser(organizationId, "lost-a");
    UUID lostB = createUser(organizationId, "lost-b");
    UUID lostC = createUser(organizationId, "lost-c");
    Group group = persistOrgUnit("dir-guid-1", "Referat 50", keptMember, lostA, lostB, lostC);
    group.dissolve(Instant.now());
    groupRepository.save(group);

    // The directory reports the unit again (reactivation) but with only 1 of its 4 frozen
    // members - a 75% loss within the only group that exists.
    directoryClient.respondWith(
        new DirectoryGroup("dir-guid-1", "Referat 50", null, Set.of("kept")));

    DirectorySyncReportResponse report = directorySyncService.run(organizationId);

    assertThat(report.getOutcome()).isEqualTo(DirectorySyncOutcome.ABORTED_THRESHOLD);
    assertThat(report.getChangedFraction()).isEqualTo(0.75);
    Group reloaded = groupRepository.findById(group.getId()).orElseThrow();
    // Aborted - still dissolved, membership untouched.
    assertThat(reloaded.isDissolved()).isTrue();
    assertThat(membershipRepository.findByGroupId(reloaded.getId())).hasSize(4);
  }

  @Test
  void aMassDissolutionOfMemberlessGroupsIsCaughtByTheGroupCountMeasureEvenAtZeroMembershipRisk() {
    // 50 ORG_UNIT groups with no members at all (the routine state during an introduction phase,
    // before curators and memberships are populated - review of PR #297). The directory now
    // reports only one of them; the membership-based measure sees changedFraction = 0.0 either
    // way, since no membership row is at stake.
    List<DirectoryGroup> stillReported = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      persistOrgUnit("dir-guid-" + i, "Referat " + i);
      if (i == 0) {
        stillReported.add(new DirectoryGroup("dir-guid-" + i, "Referat " + i, null, Set.of()));
      }
    }
    directoryClient.respondWith(stillReported.toArray(new DirectoryGroup[0]));

    DirectorySyncReportResponse report = directorySyncService.run(organizationId);

    assertThat(report.getOutcome()).isEqualTo(DirectorySyncOutcome.ABORTED_THRESHOLD);
    assertThat(report.getChangedFraction()).isEqualTo(0.98);
    long dissolvedCount =
        groupRepository.findByOrganizationId(organizationId).stream()
            .filter(Group::isDissolved)
            .count();
    assertThat(dissolvedCount).isZero();
  }

  @Test
  void aLegitimateSingleDissolutionInASmallOrganizationPassesThroughUnblocked() {
    // The exact scenario a second review of PR #297 measured against real Postgres: 3 active
    // ORG_UNIT groups, well under MIN_ACTIVE_GROUPS_FOR_GROUP_MEASURE and
    // MIN_DISSOLUTIONS_FOR_GROUP_MEASURE, two of them unchanged with real members and one an
    // empty placeholder unit that legitimately disappears from the directory. No membership is at
    // risk at all - without the floor on the group-count measure, this run used to abort
    // permanently (1 of 3 active groups = 33%, above the 30% default threshold, with no per-run
    // override and no way for a later run of the same small organization to ever pass).
    Set<String> teamASubjects = new HashSet<>();
    UUID[] teamAMembers = new UUID[10];
    for (int i = 0; i < teamAMembers.length; i++) {
      teamAMembers[i] = createUser(organizationId, "team-a-" + i);
      teamASubjects.add("team-a-" + i);
    }
    persistOrgUnit("dir-team-a", "Team A", teamAMembers);

    Set<String> teamBSubjects = new HashSet<>();
    UUID[] teamBMembers = new UUID[10];
    for (int i = 0; i < teamBMembers.length; i++) {
      teamBMembers[i] = createUser(organizationId, "team-b-" + i);
      teamBSubjects.add("team-b-" + i);
    }
    persistOrgUnit("dir-team-b", "Team B", teamBMembers);

    persistOrgUnit("dir-placeholder", "Platzhalter"); // empty, about to legitimately disappear

    directoryClient.respondWith(
        new DirectoryGroup("dir-team-a", "Team A", null, teamASubjects),
        new DirectoryGroup("dir-team-b", "Team B", null, teamBSubjects));

    DirectorySyncReportResponse report = directorySyncService.run(organizationId);

    assertThat(report.getOutcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
    assertThat(report.getChangedFraction()).isEqualTo(0.0);
    assertThat(report.getGroupsDissolved()).hasSize(1);
  }

  // ---------------------------------------------------------------------------------------
  // Dry run persists its outcome - review of PR #297: under the class-level readOnly transaction
  // dryRun used to run in, Hibernate's FlushMode.MANUAL silently dropped the status insert.
  // ---------------------------------------------------------------------------------------

  @Test
  void dryRunPersistsItsOutcomeEvenThoughItNeverWritesGroupData() {
    UUID member = createUser(organizationId, "member-1");
    persistOrgUnit("dir-guid-1", "Referat 50", member);
    directoryClient.respondWith(
        new DirectoryGroup("dir-guid-1", "Referat 50", null, Set.of("member-1")));

    directorySyncService.dryRun(organizationId);

    DirectorySyncStatus status =
        statusRepository.findByOrganizationId(organizationId).orElseThrow();
    assertThat(status.getLastOutcome()).isEqualTo(DirectorySyncOutcome.DRY_RUN);
  }

  @Test
  void dryRunAgainstAnUnreachableDirectoryStillRecordsTheOutcomeDurably() {
    directoryClient.failWith("timeout");

    DirectorySyncReportResponse report = directorySyncService.dryRun(organizationId);

    assertThat(report.getOutcome()).isEqualTo(DirectorySyncOutcome.UNREACHABLE);
    DirectorySyncStatus status =
        statusRepository.findByOrganizationId(organizationId).orElseThrow();
    assertThat(status.getLastOutcome()).isEqualTo(DirectorySyncOutcome.UNREACHABLE);
  }

  // ---------------------------------------------------------------------------------------
  // Parent group resolution - review of PR #297: must not depend on directory response order,
  // and must apply to existing groups across separate runs, not only at creation time.
  // ---------------------------------------------------------------------------------------

  @Test
  void resolvesAParentLinkRegardlessOfWhetherTheChildOrTheParentIsReportedFirst() {
    // The child is listed before the parent in the very same snapshot - a real LDAP response has
    // no guaranteed order.
    directoryClient.respondWith(
        new DirectoryGroup("dir-child", "Unterabteilung", "dir-parent", Set.of()),
        new DirectoryGroup("dir-parent", "Abteilung", null, Set.of()));

    directorySyncService.run(organizationId);

    Group parent =
        groupRepository.findByOrganizationId(organizationId).stream()
            .filter(g -> "dir-parent".equals(g.getExternalId()))
            .findFirst()
            .orElseThrow();
    Group child =
        groupRepository.findByOrganizationId(organizationId).stream()
            .filter(g -> "dir-child".equals(g.getExternalId()))
            .findFirst()
            .orElseThrow();
    assertThat(child.getParentGroupId()).isEqualTo(parent.getId());
  }

  @Test
  void aReorganisationReassignsAnExistingGroupsParentOnALaterRun() {
    // Run 1: two independent, top-level groups.
    directoryClient.respondWith(
        new DirectoryGroup("dir-a", "Referat A", null, Set.of()),
        new DirectoryGroup("dir-b", "Referat B", null, Set.of()));
    directorySyncService.run(organizationId);
    Group groupA =
        groupRepository.findByOrganizationId(organizationId).stream()
            .filter(g -> "dir-a".equals(g.getExternalId()))
            .findFirst()
            .orElseThrow();
    assertThat(groupA.getParentGroupId()).isNull();

    // Run 2: a reorganisation puts A under B.
    directoryClient.respondWith(
        new DirectoryGroup("dir-a", "Referat A", "dir-b", Set.of()),
        new DirectoryGroup("dir-b", "Referat B", null, Set.of()));
    directorySyncService.run(organizationId);

    Group groupB =
        groupRepository.findByOrganizationId(organizationId).stream()
            .filter(g -> "dir-b".equals(g.getExternalId()))
            .findFirst()
            .orElseThrow();
    Group reloadedA = groupRepository.findById(groupA.getId()).orElseThrow();
    assertThat(reloadedA.getParentGroupId()).isEqualTo(groupB.getId());
  }

  // ---------------------------------------------------------------------------------------
  // Failed apply must not be recorded as APPLIED - review of PR #297: an earlier version wrote
  // the status via a REQUIRES_NEW transaction that committed before the surrounding apply
  // transaction could roll back, so a run that failed to actually apply could still end up
  // durably marked APPLIED.
  // ---------------------------------------------------------------------------------------

  @Test
  void aFailedApplyDoesNotRecordAnAppliedStatus() {
    UUID member = createUser(organizationId, "member-1");
    persistOrgUnit("dir-guid-1", "Referat 50", member);
    directoryClient.respondWith(
        new DirectoryGroup("dir-guid-1", "Referat 50", null, Set.of("member-1")));
    directorySyncService.dryRun(organizationId);
    assertThat(statusRepository.findByOrganizationId(organizationId).orElseThrow().getLastOutcome())
        .isEqualTo(DirectorySyncOutcome.DRY_RUN);

    // A directory-supplied group name exceeding groups.name's varchar(255) makes the CREATE fail
    // at commit time - a real, if unusual, directory response, not a test-only trick.
    String tooLongName = "x".repeat(300);
    directoryClient.respondWith(
        new DirectoryGroup("dir-guid-1", "Referat 50", null, Set.of("member-1")),
        new DirectoryGroup("dir-guid-9", tooLongName, null, Set.of()));

    assertThatThrownBy(() -> directorySyncService.run(organizationId))
        .isInstanceOf(RuntimeException.class);

    DirectorySyncStatus status =
        statusRepository.findByOrganizationId(organizationId).orElseThrow();
    assertThat(status.getLastOutcome()).isEqualTo(DirectorySyncOutcome.DRY_RUN);
    assertThat(status.getLastAppliedAt()).isNull();
  }

  // ---------------------------------------------------------------------------------------
  // The report must name who would lose membership, not only who would gain it - review of PR
  // #297: the removed side used to carry a null display name unconditionally.
  // ---------------------------------------------------------------------------------------

  @Test
  void theDryRunReportNamesTheUsersWhoWouldLoseMembership() {
    UUID keep = createUser(organizationId, "keep");
    UUID leaving = createUser(organizationId, "leaving");
    persistOrgUnit("dir-guid-1", "Referat 50", keep, leaving);
    directoryClient.respondWith(
        new DirectoryGroup("dir-guid-1", "Referat 50", null, Set.of("keep")));

    DirectorySyncReportResponse report = directorySyncService.dryRun(organizationId);

    assertThat(report.getMembershipChanges()).hasSize(1);
    DirectorySyncMembershipChange change = report.getMembershipChanges().get(0);
    assertThat(change.getRemoved()).hasSize(1);
    assertThat(change.getRemoved().get(0).getUserId()).isEqualTo(leaving);
    assertThat(change.getRemoved().get(0).getDisplayName()).isEqualTo("Test User");
  }

  // ---------------------------------------------------------------------------------------
  // Status
  // ---------------------------------------------------------------------------------------

  @Test
  void statusReflectsTheMostRecentRunIncludingUnreachableAttempts() {
    assertThat(directorySyncService.getStatus(organizationId).getLastOutcome()).isNull();

    directoryClient.failWith("timeout");
    directorySyncService.run(organizationId);

    assertThat(directorySyncService.getStatus(organizationId).getLastOutcome())
        .isEqualTo(DirectorySyncOutcome.UNREACHABLE);
    assertThat(directorySyncService.getStatus(organizationId).getLastAppliedAt()).isNull();
  }
}
