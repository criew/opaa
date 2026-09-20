package io.opaa.integration.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.DirectoryConnectorType;
import io.opaa.api.types.DirectorySyncOutcome;
import io.opaa.api.types.GroupKind;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.group.Group;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupMembershipRepository;
import io.opaa.group.GroupRepository;
import io.opaa.group.sync.DirectoryGroup;
import io.opaa.group.sync.DirectorySnapshot;
import io.opaa.group.sync.DirectorySyncPendingPlanRepository;
import io.opaa.group.sync.DirectorySyncService;
import io.opaa.group.sync.DirectorySyncStatusRepository;
import io.opaa.group.sync.SyncReport;
import io.opaa.group.sync.connector.DirectoryConnectorRepository;
import io.opaa.group.sync.connector.DirectoryConnectorService;
import io.opaa.group.sync.connector.ProviderDirectoryClient;
import io.opaa.organization.Organization;
import io.opaa.permission.GroupMembershipHistoryRepository;
import io.opaa.test.FakeDirectoryClient;
import io.opaa.test.OpaaIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The acceptance criteria of #1817 end to end: against a real Keycloak, a run creates the
 * organizational units and their memberships, a second run without a change in the directory does
 * nothing, and a group renamed in the directory keeps its identity - and with it its grants.
 *
 * <p><b>Where the seam is.</b> {@code FakeDirectoryClient} is the {@code @Primary} {@link
 * io.opaa.group.sync.DirectoryClient} of the shared context, so a run cannot be made to call the
 * productive one through the bean. This class therefore reads the directory through the productive
 * {@link ProviderDirectoryClient} itself - the real stored access, the real decryption, the real
 * address policy, the real Admin API - and hands exactly that snapshot to the run. Everything
 * between the snapshot and the database is production code; the one thing this cannot show is that
 * {@link ProviderDirectoryClient} is <em>the</em> bean the run resolves, which {@code
 * DirectoryConnectorIntegrationTest} does not show either. That gap is the price of the shared
 * context signature (AGENTS.md, "Spring-Testkontexte").
 */
@OpaaIntegrationTest
class KeycloakDirectorySyncTest {

  private static final UUID ORGANIZATION_ID = Organization.DEFAULT_ID;

  @Autowired private DirectorySyncService directorySyncService;
  @Autowired private DirectoryConnectorService connectorService;
  @Autowired private DirectoryConnectorRepository connectorRepository;
  @Autowired private ProviderDirectoryClient providerDirectoryClient;
  @Autowired private FakeDirectoryClient directoryClient;
  @Autowired private OidcProviderRepository providerRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private GroupRepository groupRepository;
  @Autowired private GroupMembershipRepository membershipRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;
  @Autowired private DirectorySyncStatusRepository statusRepository;
  @Autowired private DirectorySyncPendingPlanRepository pendingPlanRepository;

  private KeycloakFixture keycloak;
  private UUID providerId;
  private UUID actorId;
  private final List<UUID> createdUserIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    cleanUp();
    keycloak = KeycloakFixture.get();
    OidcProvider provider =
        new OidcProvider(
            "Keycloak " + UUID.randomUUID(),
            keycloak.issuerUri(),
            "opaa-frontend",
            null,
            OidcClaimMapping.keycloakDefaults());
    provider.configureDirectorySync(true, 360);
    providerId = providerRepository.save(provider).getId();
    actorId = createUser("verwaltung-" + UUID.randomUUID());
    // The Keycloak user id IS the sub of its tokens (ADR-0036, Entscheidung 3), so an OPAA account
    // of this issuer carries it verbatim - no mapping rule anywhere.
    createUser(keycloak.user1Id());
    createUser(keycloak.user2Id());
    createUser(keycloak.user3Id());
    connectorService.save(
        ORGANIZATION_ID,
        actorId,
        providerId,
        DirectoryConnectorType.KEYCLOAK,
        null,
        KeycloakFixture.DIRECTORY_CLIENT_ID,
        KeycloakFixture.DIRECTORY_CLIENT_SECRET);
  }

  @AfterEach
  void tearDown() {
    cleanUp();
  }

  private void cleanUp() {
    if (providerId != null) {
      pendingPlanRepository
          .findByOrganizationIdAndProviderId(ORGANIZATION_ID, providerId)
          .ifPresent(pendingPlanRepository::delete);
      statusRepository
          .findByOrganizationIdAndProviderId(ORGANIZATION_ID, providerId)
          .ifPresent(statusRepository::delete);
      List<Group> ownGroups =
          groupRepository.findByOrganizationId(ORGANIZATION_ID).stream()
              .filter(group -> providerId.equals(group.getProviderId()))
              .toList();
      membershipRepository.deleteAll(
          ownGroups.stream()
              .flatMap(group -> membershipRepository.findByGroupId(group.getId()).stream())
              .toList());
      groupRepository.deleteAll(ownGroups);
      connectorRepository.findByProviderId(providerId).ifPresent(connectorRepository::delete);
      providerRepository.deleteById(providerId);
      providerId = null;
    }
    membershipHistoryRepository.deleteByUserIdIn(List.copyOf(createdUserIds));
    userRepository.deleteAllById(createdUserIds);
    createdUserIds.clear();
    actorId = null;
  }

  // ---------------------------------------------------------------------------------------

  /** Acceptance criterion 1: groups and memberships arise; a second, unchanged run does nothing. */
  @Test
  void aRunAgainstARealKeycloakCreatesTheUnitsAndASecondRunChangesNothing() throws Exception {
    SyncReport first = runAgainstKeycloak();

    assertThat(first.outcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
    Group referat = groupByExternalId(keycloak.referat50GroupId());
    assertThat(referat.getKind()).isEqualTo(GroupKind.ORG_UNIT);
    assertThat(referat.getName()).isEqualTo("Referat 50");
    assertThat(referat.getSourcePath()).isEqualTo("/Haus/Referat 50");
    assertThat(referat.getProviderId()).isEqualTo(providerId);
    assertThat(membershipRepository.findByGroupId(referat.getId()))
        .extracting(GroupMembership::getUserId)
        .containsExactlyInAnyOrder(
            userIdOfSubject(keycloak.user1Id()), userIdOfSubject(keycloak.user2Id()));
    assertThat(groupByExternalId(keycloak.hausGroupId()).getParentGroupId()).isNull();
    assertThat(groupByExternalId(keycloak.referat50GroupId()).getParentGroupId())
        .isEqualTo(groupByExternalId(keycloak.hausGroupId()).getId());

    UUID referatIdAfterFirstRun = referat.getId();
    SyncReport second = runAgainstKeycloak();

    assertThat(second.outcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
    assertThat(second.groupsCreated()).isEmpty();
    assertThat(second.groupsRenamed()).isEmpty();
    assertThat(second.groupsDissolved()).isEmpty();
    assertThat(second.membershipsAdded()).isZero();
    assertThat(second.membershipsRemoved()).isZero();
    assertThat(groupByExternalId(keycloak.referat50GroupId()).getId())
        .isEqualTo(referatIdAfterFirstRun);
  }

  /**
   * Acceptance criterion 2: a Keycloak department that only holds subgroups is an empty group here,
   * and the diff report of the first run names that count before anything is applied.
   */
  @Test
  void aDepartmentWithoutDirectMembersBecomesAnEmptyGroupAndTheReportNamesIt() throws Exception {
    SyncReport report = runAgainstKeycloak();

    assertThat(report.groupsCreated())
        .filteredOn(change -> change.externalId().equals(keycloak.hausGroupId()))
        .singleElement()
        .satisfies(
            change -> {
              assertThat(change.name()).isEqualTo("Haus");
              assertThat(change.memberCount()).isZero();
            });
    assertThat(
            membershipRepository.findByGroupId(groupByExternalId(keycloak.hausGroupId()).getId()))
        .isEmpty();
  }

  /**
   * Acceptance criterion 3: the match is on Keycloak's group id, so a rename in the directory keeps
   * the OPAA group - and every grant pointing at it - in place.
   */
  @Test
  void aGroupRenamedInTheDirectoryKeepsItsIdentity() throws Exception {
    runAgainstKeycloak();
    UUID idBeforeRename = groupByExternalId(keycloak.externGroupId()).getId();

    keycloak.renameGroup(keycloak.externGroupId(), "Externe Stellen");
    try {
      SyncReport report = runAgainstKeycloak();

      assertThat(report.groupsRenamed())
          .filteredOn(change -> change.externalId().equals(keycloak.externGroupId()))
          .singleElement()
          .satisfies(
              change -> {
                assertThat(change.previousName()).isEqualTo("Extern");
                assertThat(change.name()).isEqualTo("Externe Stellen");
              });
      Group renamed = groupByExternalId(keycloak.externGroupId());
      assertThat(renamed.getId()).isEqualTo(idBeforeRename);
      assertThat(renamed.getName()).isEqualTo("Externe Stellen");
      assertThat(renamed.getSourcePath()).isEqualTo("/Externe Stellen");
    } finally {
      keycloak.renameGroup(keycloak.externGroupId(), "Extern");
    }
  }

  /** Acceptance criterion 5: every member of a provider group carries that provider's issuer. */
  @Test
  void everyMemberOfAProviderGroupBelongsToThatProvidersIssuer() throws Exception {
    runAgainstKeycloak();

    String issuer = providerRepository.findById(providerId).orElseThrow().getIssuerUri();
    for (Group group : groupRepository.findByOrganizationId(ORGANIZATION_ID)) {
      if (!providerId.equals(group.getProviderId())) {
        continue;
      }
      for (GroupMembership membership : membershipRepository.findByGroupId(group.getId())) {
        assertThat(userRepository.findById(membership.getUserId()).orElseThrow().getIssuer())
            .isEqualTo(issuer);
      }
    }
  }

  // ---------------------------------------------------------------------------------------
  // Fixture
  // ---------------------------------------------------------------------------------------

  /**
   * Reads the directory through the productive client and lets the run work on exactly that
   * snapshot - see the class Javadoc for why the pass-through exists.
   */
  private SyncReport runAgainstKeycloak() throws Exception {
    DirectorySnapshot snapshot = providerDirectoryClient.fetchGroups(ORGANIZATION_ID, providerId);
    directoryClient.respondWithFor(providerId, snapshot.groups().toArray(DirectoryGroup[]::new));
    return directorySyncService.run(ORGANIZATION_ID, providerId);
  }

  private Group groupByExternalId(String externalId) {
    return groupRepository.findByOrganizationId(ORGANIZATION_ID).stream()
        .filter(group -> externalId.equals(group.getExternalId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no group with external id " + externalId));
  }

  private UUID userIdOfSubject(String subject) {
    return createdUserIds.stream()
        .map(id -> userRepository.findById(id).orElseThrow())
        .filter(user -> user.getSubject().equals(subject))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no user with subject " + subject))
        .getId();
  }

  private UUID createUser(String subject) {
    User user =
        new User(subject, keycloak.issuerUri(), subject + "@example.org", "Test Person " + subject);
    user.setOrganizationId(ORGANIZATION_ID);
    UUID id = userRepository.save(user).getId();
    createdUserIds.add(id);
    return id;
  }
}
