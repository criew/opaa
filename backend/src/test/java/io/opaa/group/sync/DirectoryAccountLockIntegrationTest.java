package io.opaa.group.sync;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.DirectorySyncOutcome;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.AccountState;
import io.opaa.permission.AccountStateHistory;
import io.opaa.permission.AccountStateHistoryCause;
import io.opaa.permission.AccountStateHistoryRepository;
import io.opaa.test.FakeDirectoryClient;
import io.opaa.test.OpaaIntegrationTest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The account status a run takes over from the directory (#1818, ADR-0036 Entscheidungen 3, 6 and
 * 8): an account the directory reports as disabled - or stops reporting at all - loses its access
 * at the next run, an empty account list locks nobody, an implausibly large one waits for a
 * confirmation, and the last login-capable system administrator is never locked.
 *
 * <p>Runs against the real, versioned Liquibase schema for the reason {@link
 * DirectorySyncServiceIntegrationTest} states: {@code account_state_history} carries a {@code
 * RESTRICT} foreign key that only the changelog creates.
 */
@OpaaIntegrationTest
class DirectoryAccountLockIntegrationTest {

  @Autowired private DirectorySyncService directorySyncService;
  @Autowired private UserRepository userRepository;
  @Autowired private OidcProviderRepository providerRepository;
  @Autowired private AccountStateHistoryRepository accountStateHistoryRepository;
  @Autowired private DirectorySyncStatusRepository statusRepository;
  @Autowired private DirectorySyncPendingPlanRepository pendingPlanRepository;
  @Autowired private FakeDirectoryClient directoryClient;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private static final List<UUID> createdUserIds = new ArrayList<>();
  private static final List<UUID> createdProviderIds = new ArrayList<>();
  private static final List<UUID> createdOrganizationIds = new ArrayList<>();

  private UUID organizationId;
  private OidcProvider syncProvider;

  @BeforeEach
  void setUp() {
    wipe();
    organizationId = Organization.DEFAULT_ID;
    syncProvider =
        providerRepository.save(
            new OidcProvider(
                "Verzeichnis " + UUID.randomUUID(),
                "https://idp.example/realms/" + UUID.randomUUID(),
                "opaa-frontend",
                null,
                OidcClaimMapping.keycloakDefaults()));
    syncProvider.configureDirectorySync(true, 360);
    providerRepository.save(syncProvider);
    createdProviderIds.add(syncProvider.getId());
    directoryClient.respondWith();
  }

  @AfterEach
  void tearDown() {
    wipe();
  }

  private void wipe() {
    List<UUID> organizationIds = new ArrayList<>(createdOrganizationIds);
    organizationIds.add(Organization.DEFAULT_ID);
    for (UUID organization : organizationIds) {
      pendingPlanRepository.deleteAll(pendingPlanRepository.findByOrganizationId(organization));
      statusRepository.deleteAll(statusRepository.findByOrganizationId(organization));
    }
    List<UUID> userIds = List.copyOf(createdUserIds);
    if (!userIds.isEmpty()) {
      // user_id is ON DELETE RESTRICT - the history goes before the accounts it names.
      accountStateHistoryRepository.deleteByUserIdIn(userIds);
    }
    userRepository.deleteAllById(userIds);
    createdUserIds.clear();
    createdProviderIds.forEach(providerRepository::deleteById);
    createdProviderIds.clear();
    for (UUID organization : createdOrganizationIds) {
      // fk_audit_log_organization is ON DELETE RESTRICT, and the run this class fires writes its
      // entries against this organization - same cleanup as AuditLogServiceIntegrationTest, and
      // for the same reason it goes through JdbcTemplate rather than a repository.
      jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organization);
      jdbcTemplate.update(
          "DELETE FROM audit_actor_pseudonyms WHERE organization_id = ?", organization);
      organizationRepository.deleteById(organization);
    }
    createdOrganizationIds.clear();
  }

  @Test
  void anAccountTheDirectoryReportsAsDisabledLosesItsAccessAndKeepsALosslessHistory() {
    UUID leaving = createAccount("subject-gone", SystemRole.USER);
    UUID staying = createAccount("subject-here", SystemRole.USER);
    respondWithAccounts(
        new DirectoryAccount("subject-gone", false), new DirectoryAccount("subject-here", true));

    SyncReport report = directorySyncService.run(organizationId, syncProvider.getId());

    assertThat(report.outcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
    assertThat(report.accountsLocked()).extracting(UserRef::id).containsExactly(leaving);
    assertThat(userRepository.findById(leaving).orElseThrow().isDirectoryLocked()).isTrue();
    assertThat(userRepository.findById(staying).orElseThrow().isDirectoryLocked()).isFalse();

    List<AccountStateHistory> chain = chainOf(leaving);
    assertThat(chain)
        .as("the chain starts at the account's creation, without a gap before the lock")
        .hasSize(2);
    assertThat(chain.get(0).getState()).isEqualTo(AccountState.ACTIVE);
    assertThat(chain.get(0).getCause()).isEqualTo(AccountStateHistoryCause.ACCOUNT_CREATED);
    assertThat(chain.get(0).getValidTo()).isEqualTo(chain.get(1).getValidFrom());
    assertThat(chain.get(1).getState()).isEqualTo(AccountState.LOCKED);
    assertThat(chain.get(1).getValidTo()).isNull();
    assertThat(chainOf(staying)).as("an untouched account writes no interval").isEmpty();
  }

  /**
   * "Ausgeschieden" looks exactly like this from here: the directory stops reporting the account.
   */
  @Test
  void anAccountTheDirectoryNoLongerReportsAtAllIsLockedToo() {
    UUID leaving = createAccount("subject-gone", SystemRole.USER);
    createAccount("subject-here", SystemRole.USER);
    respondWithAccounts(new DirectoryAccount("subject-here", true));

    directorySyncService.run(organizationId, syncProvider.getId());

    assertThat(userRepository.findById(leaving).orElseThrow().isDirectoryLocked()).isTrue();
  }

  @Test
  void aLockIsTakenBackOnceTheDirectoryReportsTheAccountAsEnabledAgain() {
    UUID account = createAccount("subject-gone", SystemRole.USER);
    respondWithAccounts(new DirectoryAccount("subject-gone", false));
    directorySyncService.run(organizationId, syncProvider.getId());

    respondWithAccounts(new DirectoryAccount("subject-gone", true));
    SyncReport report = directorySyncService.run(organizationId, syncProvider.getId());

    assertThat(report.accountsUnlocked()).extracting(UserRef::id).containsExactly(account);
    assertThat(userRepository.findById(account).orElseThrow().isDirectoryLocked()).isFalse();
    List<AccountStateHistory> chain = chainOf(account);
    assertThat(chain).hasSize(3);
    assertThat(chain.get(1).getValidTo())
        .as("both instants of the lock are on record, without a gap")
        .isEqualTo(chain.get(2).getValidFrom());
    assertThat(chain.get(2).getState()).isEqualTo(AccountState.ACTIVE);
    assertThat(chain.get(2).getCause()).isEqualTo(AccountStateHistoryCause.DIRECTORY_UNLOCKED);
    assertThat(chain.get(2).getValidTo()).isNull();
  }

  /** The empty-result protection of #237, on the account side: a hard abort, not a plan. */
  @Test
  void anEmptyAccountListLocksNobodyAndLeavesNoPlanToConfirm() {
    UUID account = createAccount("subject-gone", SystemRole.USER);
    directoryClient.respondWith(List.of(), List.of());

    SyncReport report = directorySyncService.run(organizationId, syncProvider.getId());

    assertThat(report.outcome()).isEqualTo(DirectorySyncOutcome.ABORTED_EMPTY_RESULT);
    assertThat(report.accountsLocked()).isEmpty();
    assertThat(userRepository.findById(account).orElseThrow().isDirectoryLocked()).isFalse();
    assertThat(
            pendingPlanRepository.findByOrganizationIdAndProviderId(
                organizationId, syncProvider.getId()))
        .isEmpty();
  }

  /** A connector that reports no account status at all never touches an account's state. */
  @Test
  void aDirectoryWithoutAccountStatusChangesNoAccountState() {
    UUID account = createAccount("subject-gone", SystemRole.USER);
    directoryClient.respondWith();

    SyncReport report = directorySyncService.run(organizationId, syncProvider.getId());

    assertThat(report.outcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
    assertThat(userRepository.findById(account).orElseThrow().isDirectoryLocked()).isFalse();
    assertThat(chainOf(account)).isEmpty();
  }

  /**
   * Account locks carry the same threshold and the same confirmation path as membership withdrawals
   * (ADR-0036, Entscheidung 3): a run that would lock most of a provider's accounts waits for a
   * decision, and what it presents is exactly what a confirmation applies.
   */
  @Test
  void anImplausiblyLargeNumberOfLocksWaitsForAConfirmationAndIsAppliedByIt() {
    List<UUID> accounts = new ArrayList<>();
    List<DirectoryAccount> reported = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      accounts.add(createAccount("subject-" + i, SystemRole.USER));
      reported.add(new DirectoryAccount("subject-" + i, i >= 6));
    }
    directoryClient.respondWith(List.of(), reported);

    SyncReport pending = directorySyncService.run(organizationId, syncProvider.getId());

    assertThat(pending.outcome()).isEqualTo(DirectorySyncOutcome.PENDING_CONFIRMATION);
    assertThat(pending.accountsLocked()).hasSize(6);
    assertThat(userRepository.findById(accounts.get(0)).orElseThrow().isDirectoryLocked())
        .as("nothing is applied before the decision")
        .isFalse();
    DirectorySyncPendingPlan plan =
        pendingPlanRepository
            .findByOrganizationIdAndProviderId(organizationId, syncProvider.getId())
            .orElseThrow();
    assertThat(plan.getAccountsLocked()).isEqualTo(6);

    SyncReport confirmed =
        directorySyncService.confirmPlan(
            organizationId, syncProvider.getId(), plan.getId(), null, "Umstellung abgestimmt");

    assertThat(confirmed.outcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
    assertThat(userRepository.findById(accounts.get(0)).orElseThrow().isDirectoryLocked()).isTrue();
    assertThat(userRepository.findById(accounts.get(6)).orElseThrow().isDirectoryLocked())
        .isFalse();
  }

  /**
   * The count floor of the account measure is lower than the one for dissolved groups: in a house
   * of three accounts, a run that would lock all three waits for a decision instead of leaving
   * three people before a locked access (ADR-0036, Entscheidung 3, Personalrat C1).
   */
  @Test
  void aSmallHouseLosingEveryAccountAtOnceStillWaitsForAConfirmation() {
    createAccount("subject-a", SystemRole.USER);
    createAccount("subject-b", SystemRole.USER);
    UUID third = createAccount("subject-c", SystemRole.USER);
    respondWithAccounts(
        new DirectoryAccount("subject-a", false),
        new DirectoryAccount("subject-b", false),
        new DirectoryAccount("subject-c", false));

    SyncReport report = directorySyncService.run(organizationId, syncProvider.getId());

    assertThat(report.outcome()).isEqualTo(DirectorySyncOutcome.PENDING_CONFIRMATION);
    assertThat(userRepository.findById(third).orElseThrow().isDirectoryLocked()).isFalse();
  }

  /** A single departure stays a routine change in a house of any size - the floor's other half. */
  @Test
  void aSingleDepartureInASmallHouseIsAppliedWithoutAConfirmation() {
    UUID leaving = createAccount("subject-gone", SystemRole.USER);
    createAccount("subject-here", SystemRole.USER);
    respondWithAccounts(
        new DirectoryAccount("subject-gone", false), new DirectoryAccount("subject-here", true));

    SyncReport report = directorySyncService.run(organizationId, syncProvider.getId());

    assertThat(report.outcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
    assertThat(userRepository.findById(leaving).orElseThrow().isDirectoryLocked()).isTrue();
  }

  /**
   * A departing administrator is locked like anyone else while another login-capable one remains -
   * the run consults {@code LocalAdminAvailabilityGuard} and is not blocked by it. That the guard
   * withholds the lock of the <em>last</em> one is exercised in {@code
   * DirectorySyncPlanExecutorTest}, where the number of administrators of the installation is not
   * whatever the rest of the suite left behind.
   */
  @Test
  void anAdministratorIsLockedWhileAnotherLoginCapableOneRemains() {
    UUID leaving = createAccount("subject-admin-gone", SystemRole.SYSTEM_ADMIN);
    createAccount("subject-admin-here", SystemRole.SYSTEM_ADMIN);
    respondWithAccounts(
        new DirectoryAccount("subject-admin-gone", false),
        new DirectoryAccount("subject-admin-here", true));

    SyncReport report = directorySyncService.run(organizationId, syncProvider.getId());

    assertThat(report.accountLocksWithheld()).isEmpty();
    assertThat(report.accountsLocked()).extracting(UserRef::id).containsExactly(leaving);
    assertThat(userRepository.findById(leaving).orElseThrow().isDirectoryLocked()).isTrue();
  }

  /**
   * The promise of ADR-0036, Entscheidung 6 against the <b>real</b> guard: the lock of the last
   * login-capable system administrator is withheld, and <b>everything else of the run is still
   * applied</b>. Against a mocked guard the second half of that sentence is untestable - the guard
   * is {@code @Transactional(MANDATORY)} and takes part in the run's transaction, so how it refuses
   * decides whether the run survives its own commit.
   *
   * <p>In an organization of its own, because "no other login-capable administrator" is not a state
   * the default organization can be brought into: the suite's dev accounts live there.
   */
  @Test
  void theLastLoginCapableAdministratorIsWithheldAndTheRestOfTheRunStillApplies() {
    UUID isolated = createOrganization();
    UUID admin = createAccount(isolated, "subject-admin", SystemRole.SYSTEM_ADMIN);
    UUID ordinary = createAccount(isolated, "subject-gone", SystemRole.USER);
    respondWithAccounts(
        new DirectoryAccount("subject-admin", false), new DirectoryAccount("subject-gone", false));

    SyncReport report = directorySyncService.run(isolated, syncProvider.getId());

    assertThat(report.outcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
    assertThat(report.accountLocksWithheld()).extracting(UserRef::id).containsExactly(admin);
    assertThat(userRepository.findById(admin).orElseThrow().isDirectoryLocked()).isFalse();
    assertThat(report.accountsLocked()).extracting(UserRef::id).containsExactly(ordinary);
    assertThat(userRepository.findById(ordinary).orElseThrow().isDirectoryLocked())
        .as("the withheld lock must not take the rest of the run with it")
        .isTrue();
  }

  // ---------------------------------------------------------------------------------------

  private void respondWithAccounts(DirectoryAccount... accounts) {
    directoryClient.respondWith(List.of(), List.of(accounts));
  }

  private UUID createAccount(String subject, SystemRole role) {
    return createAccount(Organization.DEFAULT_ID, subject, role);
  }

  private UUID createAccount(UUID organization, String subject, SystemRole role) {
    User user =
        new User(
            subject, syncProvider.getIssuerUri(), subject + "@example.com", "Konto " + subject);
    user.setOrganizationId(organization);
    user.setSystemRole(role);
    UUID id = userRepository.save(user).getId();
    createdUserIds.add(id);
    return id;
  }

  private UUID createOrganization() {
    UUID id = UUID.randomUUID();
    organizationRepository.save(new Organization(id, "Organisation " + id));
    createdOrganizationIds.add(id);
    return id;
  }

  private List<AccountStateHistory> chainOf(UUID userId) {
    return accountStateHistoryRepository.findAll().stream()
        .filter(interval -> interval.getUserId().equals(userId))
        .sorted(Comparator.comparing(AccountStateHistory::getValidFrom))
        .toList();
  }
}
