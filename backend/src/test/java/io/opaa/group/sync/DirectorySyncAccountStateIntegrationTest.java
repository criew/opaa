package io.opaa.group.sync;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.DirectorySyncOutcome;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.AccountState;
import io.opaa.auth.AccountStateHistory;
import io.opaa.auth.AccountStateHistoryCause;
import io.opaa.auth.AccountStateHistoryRepository;
import io.opaa.auth.AccountStateService;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.group.Group;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupMembershipRepository;
import io.opaa.group.GroupRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.GroupMembershipHistoryRepository;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.test.FakeDirectoryClient;
import io.opaa.test.OpaaIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The account half of a directory run (#1818, ADR-0036 Entscheidungen 3, 6 and 8): the directory is
 * authoritative for the state of its provider's accounts, a block ends the way in without touching
 * a single right, and it is taken back by the same mechanism that made it.
 *
 * <p><b>This class works in an organization of its own.</b> The suite shares one database, and the
 * protection of the last login-capable system administrator counts administrators per organization
 * - in the shared {@code Organization.DEFAULT_ID} the dev issuer's administrator would decide the
 * outcome of half these tests, depending on which class ran before.
 */
@OpaaIntegrationTest
class DirectorySyncAccountStateIntegrationTest {

  @Autowired private DirectorySyncService directorySyncService;
  @Autowired private AccountStateService accountStateService;
  @Autowired private AccountStateHistoryRepository accountStateHistory;
  @Autowired private GroupRepository groupRepository;
  @Autowired private GroupMembershipRepository membershipRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;
  @Autowired private GroupMembershipResolver membershipResolver;
  @Autowired private UserRepository userRepository;
  @Autowired private OidcProviderRepository providerRepository;
  @Autowired private OrganizationRepository organizations;
  @Autowired private DirectorySyncStatusRepository statusRepository;
  @Autowired private DirectorySyncPendingPlanRepository pendingPlanRepository;
  @Autowired private FakeDirectoryClient directoryClient;
  @Autowired private JdbcTemplate jdbc;

  private UUID organizationId;
  private OidcProvider provider;
  private UUID providerId;
  private final List<UUID> createdUserIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    organizationId =
        organizations.save(new Organization(UUID.randomUUID(), "Kontozustand")).getId();
    provider =
        new OidcProvider(
            "Verzeichnis " + UUID.randomUUID(),
            "https://idp.example/realms/" + UUID.randomUUID(),
            "opaa-frontend",
            null,
            OidcClaimMapping.keycloakDefaults());
    provider.configureDirectorySync(true, 360);
    providerRepository.save(provider);
    providerId = provider.getId();
    directoryClient.respondWith();
  }

  @AfterEach
  void tearDown() {
    pendingPlanRepository.deleteAll(pendingPlanRepository.findByOrganizationId(organizationId));
    statusRepository.deleteAll(statusRepository.findByOrganizationId(organizationId));
    List<Group> groups = groupRepository.findByOrganizationId(organizationId);
    if (!createdUserIds.isEmpty()) {
      // user_id is ON DELETE RESTRICT in both history tables - they go before the accounts.
      accountStateHistory.deleteByUserIdIn(createdUserIds);
      membershipHistoryRepository.deleteByUserIdIn(createdUserIds);
    }
    membershipRepository.deleteAll(
        groups.stream()
            .flatMap(group -> membershipRepository.findByGroupId(group.getId()).stream())
            .toList());
    groupRepository.deleteAll(groups);
    jdbc.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    userRepository.deleteAllById(createdUserIds);
    createdUserIds.clear();
    providerRepository.deleteById(providerId);
    organizations.deleteById(organizationId);
  }

  // ---------------------------------------------------------------------------------------
  // Blocking
  // ---------------------------------------------------------------------------------------

  @Test
  void anAccountTheDirectoryReportsAsDisabledLosesItsAccessAndKeepsEveryRight() {
    User person = createUser("sub-1");
    persistOrgUnit("dir-1", "Referat 50", person.getId());
    directoryClient.respondWithAccounts(
        List.of(new DirectoryAccount("sub-1", false)),
        new DirectoryGroup("dir-1", "Referat 50", null, null, Set.of("sub-1")));

    SyncReport report = directorySyncService.run(organizationId, providerId);

    assertThat(report.outcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
    assertThat(report.accountsBlocked()).extracting(UserRef::id).containsExactly(person.getId());
    assertThat(accountStateService.isBlocked(person.getId())).isTrue();
    assertThat(openInterval(person).getCause())
        .isEqualTo(AccountStateHistoryCause.DIRECTORY_DISABLED);
    assertThat(openInterval(person).getProviderId()).isEqualTo(providerId);
    // "Mitgliedschaften bleiben stehen" - the block ends the way in, not the rights.
    assertThat(membershipRepository.findByGroupId(orgUnit("dir-1").getId()))
        .extracting(GroupMembership::getUserId)
        .containsExactly(person.getId());
    assertThat(auditEventCount("ACCOUNT_DEACTIVATED")).isEqualTo(1);
  }

  /**
   * "Verschwindet ein Konto im Verzeichnis oder wird es dort gesperrt" (access-control.md): a
   * departure and a lock at the source are the same event here, told apart only by the cause the
   * history records.
   */
  @Test
  void anAccountTheDirectoryNoLongerReportsAtAllIsBlockedAsDeparted() {
    User person = createUser("sub-1");
    User stays = createUser("sub-2");
    directoryClient.respondWithAccounts(
        List.of(new DirectoryAccount("sub-2", true)),
        new DirectoryGroup("dir-1", "Referat 50", null, null, Set.of("sub-2")));

    SyncReport report = directorySyncService.run(organizationId, providerId);

    assertThat(report.outcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
    assertThat(report.accountsBlocked()).extracting(UserRef::id).containsExactly(person.getId());
    assertThat(openInterval(person).getCause())
        .isEqualTo(AccountStateHistoryCause.DIRECTORY_ABSENT);
    assertThat(accountStateService.isBlocked(stays.getId())).isFalse();
  }

  /**
   * The reversal of a block (Personalrat C2): the directory reports the account again, and the
   * history carries both instants without a gap - the closing boundary of the block is the opening
   * boundary of the state that follows it.
   */
  @Test
  void aBlockIsTakenBackByTheDirectoryAndTheHistoryShowsBothInstantsWithoutAGap() {
    User person = createUser("sub-1");
    directoryClient.respondWithAccounts(
        List.of(new DirectoryAccount("sub-1", false)),
        new DirectoryGroup("dir-1", "Referat 50", null, null, Set.of("sub-1")));
    directorySyncService.run(organizationId, providerId);

    directoryClient.respondWithAccounts(
        List.of(new DirectoryAccount("sub-1", true)),
        new DirectoryGroup("dir-1", "Referat 50", null, null, Set.of("sub-1")));
    SyncReport report = directorySyncService.run(organizationId, providerId);

    assertThat(report.accountsReactivated())
        .extracting(UserRef::id)
        .containsExactly(person.getId());
    assertThat(accountStateService.isBlocked(person.getId())).isFalse();
    List<AccountStateHistory> chain =
        accountStateHistory.findByUserIdOrderByValidFromAsc(person.getId());
    assertThat(chain).hasSize(2);
    assertThat(chain.get(0).getState()).isEqualTo(AccountState.BLOCKED);
    assertThat(chain.get(0).getValidTo()).isNotNull();
    assertThat(chain.get(1).getState()).isEqualTo(AccountState.ACTIVE);
    assertThat(chain.get(1).getValidTo()).isNull();
    assertThat(chain.get(1).getValidFrom()).isEqualTo(chain.get(0).getValidTo());
    assertThat(auditEventCount("ACCOUNT_REACTIVATED")).isEqualTo(1);
  }

  /** A second run over the same reading changes nothing - one block is one interval, not two. */
  @Test
  void aRepeatedReadingOfTheSameStateWritesNoSecondInterval() {
    User person = createUser("sub-1");
    directoryClient.respondWithAccounts(
        List.of(new DirectoryAccount("sub-1", false)),
        new DirectoryGroup("dir-1", "Referat 50", null, null, Set.of("sub-1")));

    directorySyncService.run(organizationId, providerId);
    SyncReport second = directorySyncService.run(organizationId, providerId);

    assertThat(second.accountsBlocked()).isEmpty();
    assertThat(accountStateHistory.findByUserIdOrderByValidFromAsc(person.getId())).hasSize(1);
  }

  // ---------------------------------------------------------------------------------------
  // The four protective mechanisms
  // ---------------------------------------------------------------------------------------

  /**
   * The empty-result protection, account half: a connector answering with no accounts at all while
   * this provider has some is reporting a failure, not a directory everybody left. Nothing is
   * applied - the group half of the same reading included, since both come from one reading.
   */
  @Test
  void anEmptyAccountListBlocksNobodyAndStopsTheGroupHalfToo() {
    User person = createUser("sub-1");
    directoryClient.respondWithAccounts(
        List.of(), new DirectoryGroup("dir-1", "Referat 50", null, null, Set.of("sub-1")));

    SyncReport report = directorySyncService.run(organizationId, providerId);

    assertThat(report.outcome()).isEqualTo(DirectorySyncOutcome.ABORTED_EMPTY_RESULT);
    assertThat(report.message()).contains("leere Kontenliste");
    assertThat(accountStateService.isBlocked(person.getId())).isFalse();
    assertThat(groupRepository.findByOrganizationIdAndKindOrgUnit(organizationId)).isEmpty();
  }

  /**
   * The threshold and the confirmation path apply to account blocks exactly as they do to
   * membership revocations (ADR-0036, Entscheidung 3; Personalrat C1).
   */
  @Test
  void aMassBlockWaitsForConfirmationAndIsAppliedOnlyAfterIt() {
    List<User> leaving = new ArrayList<>();
    for (int i = 1; i <= 5; i++) {
      leaving.add(createUser("sub-" + i));
    }
    createUser("sub-stays");
    List<DirectoryAccount> accounts = new ArrayList<>();
    for (int i = 1; i <= 5; i++) {
      accounts.add(new DirectoryAccount("sub-" + i, false));
    }
    accounts.add(new DirectoryAccount("sub-stays", true));
    directoryClient.respondWithAccounts(
        accounts, new DirectoryGroup("dir-1", "Referat 50", null, null, Set.of("sub-stays")));

    SyncReport pending = directorySyncService.run(organizationId, providerId);

    assertThat(pending.outcome()).isEqualTo(DirectorySyncOutcome.PENDING_CONFIRMATION);
    assertThat(pending.changedFraction()).isGreaterThan(0.8);
    assertThat(pending.accountsBlocked())
        .extracting(UserRef::id)
        .containsExactlyInAnyOrderElementsOf(leaving.stream().map(User::getId).toList());
    assertThat(accountStateService.isBlocked(leaving.getFirst().getId())).isFalse();

    UUID planId =
        directorySyncService.getPendingPlan(organizationId, providerId).orElseThrow().id();
    SyncReport applied =
        directorySyncService.confirmPlan(
            organizationId, providerId, planId, null, "Abteilung aufgelöst");

    assertThat(applied.outcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
    for (User person : leaving) {
      assertThat(accountStateService.isBlocked(person.getId())).isTrue();
    }
  }

  /**
   * The counterpart of the test above, and the promise of {@code access-control.md}: a single
   * departure ends the access "ohne Ticket, ohne Handgriff und ohne Bedingung". Two of three
   * accounts is 67% and would be over the threshold without the floor the account measure carries.
   */
  @Test
  void aRoutineDepartureInASmallInstallationIsAppliedWithoutAConfirmation() {
    User leaving = createUser("sub-1");
    createUser("sub-2");
    createUser("sub-3");
    directoryClient.respondWithAccounts(
        List.of(
            new DirectoryAccount("sub-1", false),
            new DirectoryAccount("sub-2", true),
            new DirectoryAccount("sub-3", true)),
        new DirectoryGroup("dir-1", "Referat 50", null, null, Set.of("sub-2", "sub-3")));

    SyncReport report = directorySyncService.run(organizationId, providerId);

    assertThat(report.outcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
    assertThat(accountStateService.isBlocked(leaving.getId())).isTrue();
  }

  /**
   * A confirmation is compared against the plan that was shown - and an account block is part of
   * that plan. Without the block in the fingerprint a confirmed run could apply a lock nobody was
   * shown.
   */
  @Test
  void aPlanWhoseAccountHalfChangedIsPresentedAgainInsteadOfApplied() {
    for (int i = 1; i <= 6; i++) {
      createUser("sub-" + i);
    }
    List<DirectoryAccount> shown = new ArrayList<>();
    for (int i = 1; i <= 5; i++) {
      shown.add(new DirectoryAccount("sub-" + i, false));
    }
    shown.add(new DirectoryAccount("sub-6", true));
    directoryClient.respondWithAccounts(
        shown, new DirectoryGroup("dir-1", "Referat 50", null, null, Set.of("sub-6")));
    directorySyncService.run(organizationId, providerId);
    UUID planId =
        directorySyncService.getPendingPlan(organizationId, providerId).orElseThrow().id();

    // Only the account half differs now - the group reading is identical.
    List<DirectoryAccount> changed = new ArrayList<>(shown);
    changed.set(4, new DirectoryAccount("sub-5", true));
    directoryClient.respondWithAccounts(
        changed, new DirectoryGroup("dir-1", "Referat 50", null, null, Set.of("sub-6")));

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    directorySyncService.confirmPlan(
                        organizationId, providerId, planId, null, "Abteilung aufgelöst")))
        .isInstanceOf(io.opaa.common.ConflictException.class)
        .hasMessageContaining("seit der Vorlage geändert");
  }

  /**
   * A connector that reports no account list at all leaves every account state alone - the run is
   * then exactly the group run it was before #1818. Without this distinction a fake or a connector
   * that cannot read accounts would claim the whole provider had departed.
   */
  @Test
  void aSnapshotWithoutAnAccountListLeavesEveryAccountStateAlone() {
    User person = createUser("sub-1");
    directoryClient.respondWith(
        new DirectoryGroup("dir-1", "Referat 50", null, null, Set.of("sub-1")));

    SyncReport report = directorySyncService.run(organizationId, providerId);

    assertThat(report.outcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
    assertThat(report.accountsBlocked()).isEmpty();
    assertThat(accountStateService.isBlocked(person.getId())).isFalse();
    assertThat(accountStateHistory.findByUserIdOrderByValidFromAsc(person.getId())).isEmpty();
  }

  // ---------------------------------------------------------------------------------------
  // The protection of the last login-capable system administrator
  // ---------------------------------------------------------------------------------------

  /**
   * ADR-0036, Entscheidung 6, Schutzregel 2: the one exception to "a lock is never refused". The
   * run leaves this single account active, reports it, and applies everything else - a refusal of
   * the whole run would be the wrong answer, since nobody can retry a run differently.
   */
  @Test
  void theLastLoginCapableSystemAdministratorIsNeverBlockedByARun() {
    User admin = createAdmin("sub-admin");
    User ordinary = createUser("sub-1");
    directoryClient.respondWithAccounts(
        List.of(new DirectoryAccount("sub-admin", false), new DirectoryAccount("sub-1", false)),
        new DirectoryGroup("dir-1", "Referat 50", null, null, Set.of()));

    directorySyncService.run(organizationId, providerId);

    assertThat(accountStateService.isBlocked(admin.getId()))
        .as("the installation must not be left without a system administrator who can sign in")
        .isFalse();
    assertThat(accountStateService.isBlocked(ordinary.getId())).isTrue();
  }

  /** With a second administrator remaining, the first one is an ordinary block. */
  @Test
  void anAdministratorIsBlockedWhileAnotherLoginCapableOneRemains() {
    User first = createAdmin("sub-admin-1");
    createAdmin("sub-admin-2");
    directoryClient.respondWithAccounts(
        List.of(
            new DirectoryAccount("sub-admin-1", false), new DirectoryAccount("sub-admin-2", true)),
        new DirectoryGroup("dir-1", "Referat 50", null, null, Set.of()));

    directorySyncService.run(organizationId, providerId);

    assertThat(accountStateService.isBlocked(first.getId())).isTrue();
  }

  // ---------------------------------------------------------------------------------------
  // What a block does to a group's reach
  // ---------------------------------------------------------------------------------------

  /**
   * ADR-0036, Entscheidung 6: a group that reaches nobody who can act is not capable. The
   * membership itself is untouched - only the count of accounts that can act narrows.
   */
  @Test
  void aGroupThatOnlyReachesBlockedAccountsCountsAsReachingNobody() {
    User person = createUser("sub-1");
    Group unit = persistOrgUnit("dir-1", "Referat 50", person.getId());
    assertThat(membershipResolver.activeMemberCount(unit.getId(), organizationId)).isEqualTo(1);
    directoryClient.respondWithAccounts(
        List.of(new DirectoryAccount("sub-1", false)),
        new DirectoryGroup("dir-1", "Referat 50", null, null, Set.of("sub-1")));

    directorySyncService.run(organizationId, providerId);

    assertThat(membershipResolver.activeMemberCount(unit.getId(), organizationId)).isZero();
    assertThat(
            membershipResolver.resolveUserIds(
                new io.opaa.permission.PermissionSubject(
                    io.opaa.api.types.PermissionSubjectType.GROUP, unit.getId(), organizationId)))
        .as("the membership itself stands - the block ends the way in, not the right")
        .containsExactly(person.getId());
  }

  // ---------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------

  private AccountStateHistory openInterval(User person) {
    return accountStateHistory.findByUserIdAndValidToIsNull(person.getId()).orElseThrow();
  }

  private Group orgUnit(String externalId) {
    return groupRepository.findByOrganizationIdAndKindOrgUnit(organizationId).stream()
        .filter(group -> externalId.equals(group.getExternalId()))
        .findFirst()
        .orElseThrow();
  }

  private long auditEventCount(String eventType) {
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT count(*) AS c FROM audit_log WHERE organization_id = ? AND event_type = ?",
            organizationId,
            eventType);
    return ((Number) row.get("c")).longValue();
  }

  private User createUser(String subject) {
    return createUser(subject, SystemRole.USER);
  }

  private User createAdmin(String subject) {
    return createUser(subject, SystemRole.SYSTEM_ADMIN);
  }

  private User createUser(String subject, SystemRole role) {
    User user = new User(subject, provider.getIssuerUri(), subject + "@example.com", "Test User");
    user.setOrganizationId(organizationId);
    user.setSystemRole(role);
    User saved = userRepository.save(user);
    createdUserIds.add(saved.getId());
    return saved;
  }

  private Group persistOrgUnit(String externalId, String name, UUID... memberIds) {
    Group group =
        new Group(
            organizationId, GroupKind.ORG_UNIT, name, null, providerId, externalId, null, null);
    for (UUID memberId : memberIds) {
      group.addMembership(new GroupMembership(memberId, organizationId));
    }
    return groupRepository.save(group);
  }
}
