package io.opaa.group.sync;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.DirectorySyncReportResponse;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.Group;
import io.opaa.group.GroupKind;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupMembershipRepository;
import io.opaa.group.GroupRepository;
import io.opaa.organization.Organization;
import java.time.Instant;
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
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Exercises {@link DirectorySyncService} against a real Postgres database with the real, versioned
 * Liquibase schema applied ({@code spring.liquibase.enabled=true}, {@code ddl-auto=none}) - not
 * Hibernate-generated DDL. Follows {@code UserServicePersonalSpaceIntegrationTest}'s pattern rather
 * than {@code GroupServiceIntegrationTest}'s: this class's dissolution/reactivation scenarios and
 * the {@code fk_group_memberships_group_organization} composite foreign key both depend on real
 * constraints that only the versioned changelog creates.
 *
 * <p>{@link FakeDirectoryClient} replaces the production {@link NoOpDirectoryClient} as the {@link
 * DirectoryClient} bean (marked {@code @Primary}, so it wins regardless of bean registration order)
 * - the one seam between the synchronisation policy under test and an actual directory, per {@link
 * DirectoryClient}'s own javadoc.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(DirectorySyncServiceIntegrationTest.TestConfig.class)
@ActiveProfiles({"local", "basic"})
@TestPropertySource(
    properties = "OPAA_AUTH_BASIC_SECRET=test-only-secret-not-used-for-anything-sensitive-1234")
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

    // Directory no longer reports dir-guid-1 at all (merged into another unit).
    directoryClient.respondWith(new DirectoryGroup("dir-guid-2", "Referat 60", null, Set.of()));

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
