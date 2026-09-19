package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.local.LocalAdminAvailabilityGuard;
import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.common.ConflictException;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The two ways a {@code SYSTEM_ADMIN} loses the role, against a real Postgres (#1349, ADR-0033
 * Entscheidung 4): the manual one ({@link UserService#updateRole}) and the token-driven one ({@link
 * TokenRoleSynchronizer}) count through the same {@link LocalAdminAvailabilityGuard} under the
 * organization's advisory lock, so with two enabled providers - one with a {@code roles_claim}, one
 * without - the organization never ends up without a login-capable administrator, not even while
 * both withdrawals run at the same time. The manual refusal is a {@link ConflictException} carrying
 * {@link LocalAdminAvailabilityGuard#ERROR_CODE} (409) and writes neither the role nor an audit
 * row.
 */
@OpaaIntegrationTest
class ManualAndTokenRoleWithdrawalIntegrationTest {

  /**
   * The waiters {@code pg_locks} shows for the organization's advisory lock: {@code classid} is the
   * namespace, {@code objid} the hashed organization id and {@code objsubid} 2 for the two-argument
   * form {@link UserRepository#lockRoleChanges} uses.
   */
  private static final String WAITING_LOCK_COUNT =
      "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND classid = ? AND objid ="
          + " hashtext(CAST(? AS text))::oid AND objsubid = 2 AND NOT granted";

  private static final long COMPLETION_TIMEOUT_SECONDS = 30;
  private static final long POLL_INTERVAL_MILLIS = 25;
  private static final int WITHDRAWALS_IN_THE_RACE = 2;

  @Autowired private UserService userService;
  @Autowired private TokenRoleSynchronizer synchronizer;
  @Autowired private LocalAdminAvailabilityGuard guard;
  @Autowired private UserRepository users;
  @Autowired private OidcProviderRepository providers;
  @Autowired private OrganizationRepository organizations;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;

  private final List<User> createdUsers = new ArrayList<>();
  private final List<OidcProvider> createdProviders = new ArrayList<>();

  private UUID organizationId;
  private OidcProvider tokenProvider;
  private OidcProvider manualProvider;
  private User tokenAdmin;
  private User manualAdmin;

  @BeforeEach
  void setUp() {
    organizationId = organizations.save(new Organization(UUID.randomUUID(), "Rollenwege")).getId();
    // the provider with a roles_claim owns its accounts' roles; the one without leaves them to the
    // manual endpoint - both enabled, so administrators of both count as login capable
    tokenProvider =
        provider(
            "Beschäftigte",
            new OidcClaimMapping(
                null, null, "realm_access.roles", "opaa-admin", "opaa-auditor", null));
    manualProvider = provider("Gäste", OidcClaimMapping.keycloakDefaults());
    tokenAdmin = admin(tokenProvider, "token");
    manualAdmin = admin(manualProvider, "manuell");
  }

  /** Removes what this method created, also when {@link #setUp} only got half-way through. */
  @AfterEach
  void tearDown() {
    if (organizationId == null) {
      return;
    }
    jdbc.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    users.deleteAll(createdUsers);
    createdUsers.clear();
    providers.deleteAll(createdProviders);
    createdProviders.clear();
    organizations.deleteById(organizationId);
    organizationId = null;
  }

  private OidcProvider provider(String displayName, OidcClaimMapping mapping) {
    OidcProvider saved =
        providers.save(
            new OidcProvider(
                displayName,
                "https://idp.example/realms/withdrawal-" + UUID.randomUUID(),
                "opaa-frontend",
                null,
                mapping));
    createdProviders.add(saved);
    return saved;
  }

  private User admin(OidcProvider provider, String subject) {
    User user =
        new User(
            subject + "-" + UUID.randomUUID(),
            provider.getIssuerUri(),
            subject + "@stadt.example",
            subject);
    user.setOrganizationId(organizationId);
    user.setSystemRole(SystemRole.SYSTEM_ADMIN);
    User saved = users.save(user);
    createdUsers.add(saved);
    return saved;
  }

  /** How many connections wait, ungranted, for this organization's role-change advisory lock. */
  private long waitingForRoleChangeLock() {
    Long count =
        jdbc.queryForObject(
            WAITING_LOCK_COUNT,
            Long.class,
            UserRepository.TOKEN_ROLE_CHANGE_LOCK_NAMESPACE,
            organizationId.toString());
    return count == null ? 0 : count;
  }

  private SystemRole storedRole(User user) {
    return users.findById(user.getId()).orElseThrow().getSystemRole();
  }

  private List<String> auditRows() {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT event_type, outcome FROM audit_log WHERE organization_id = ? ORDER BY"
                + " recorded_at, event_id",
            organizationId);
    return rows.stream().map(row -> row.get("event_type") + "/" + row.get("outcome")).toList();
  }

  private void withdrawManually() {
    userService.updateRole(manualAdmin.getId(), SystemRole.USER, CurrentUser.from(manualAdmin));
  }

  private User withdrawByToken() {
    return synchronizer.apply(tokenAdmin, tokenProvider, List.of());
  }

  @Test
  void theManualWithdrawalIsRefusedWhenTheTokenAlreadyTookTheOtherAdministrator() {
    assertThat(withdrawByToken().getSystemRole()).isEqualTo(SystemRole.USER);

    Throwable refusal = catchThrowable(this::withdrawManually);
    assertThat(refusal)
        .as(
            "the manual withdrawal of the last login-capable administrator must be refused"
                + " (ADR-0033, Entscheidung 4)")
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Systemverwalter");
    assertThat(((ConflictException) refusal).getCode())
        .isEqualTo(LocalAdminAvailabilityGuard.ERROR_CODE);

    assertThat(storedRole(manualAdmin)).isEqualTo(SystemRole.SYSTEM_ADMIN);
    assertThat(guard.countLoginCapableSystemAdmins(organizationId)).isEqualTo(1);
    // the refused change writes no role event of its own
    assertThat(auditRows()).containsExactly("SYSTEM_ADMIN_ROLE_REVOKED/SUCCESS");
  }

  @Test
  void theTokenWithdrawalIsRefusedWhenTheManualOneAlreadyTookTheOtherAdministrator() {
    withdrawManually();
    assertThat(storedRole(manualAdmin)).isEqualTo(SystemRole.USER);

    assertThat(withdrawByToken().getSystemRole()).isEqualTo(SystemRole.SYSTEM_ADMIN);

    assertThat(storedRole(tokenAdmin)).isEqualTo(SystemRole.SYSTEM_ADMIN);
    assertThat(guard.countLoginCapableSystemAdmins(organizationId)).isEqualTo(1);
    assertThat(auditRows())
        .containsExactly(
            "SYSTEM_ADMIN_ROLE_REVOKED/SUCCESS", "SYSTEM_ADMIN_ROLE_REVOCATION_REFUSED/DENIED");
  }

  /**
   * Both withdrawals at once, pinned to the interleaving that would lose the last administrator: a
   * transaction of this test holds the organization's advisory lock while both run, and {@code
   * pg_locks} must show <em>both</em> of them waiting for it. A withdrawal that does not take the
   * lock never shows up there and commits during the barrier - and two such commits are what leave
   * no administrator behind.
   */
  @Test
  void aManualAndATokenWithdrawalAtTheSameTimeLeaveExactlyOneAdministrator() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    Callable<String> manual =
        () -> {
          start.await();
          try {
            withdrawManually();
            return null;
          } catch (ConflictException e) {
            return e.getCode();
          }
        };
    Callable<SystemRole> token =
        () -> {
          start.await();
          return withdrawByToken().getSystemRole();
        };
    try {
      Future<String> manualResult = pool.submit(manual);
      Future<SystemRole> tokenResult = pool.submit(token);
      new TransactionTemplate(transactionManager)
          .executeWithoutResult(
              status -> {
                users.lockRoleChanges(organizationId);
                start.countDown();
                awaitBothWaitingForTheLock();
                assertThat(List.of(manualResult.isDone(), tokenResult.isDone()))
                    .as("neither withdrawal may commit while this test holds the lock")
                    .containsOnly(false);
              });

      String manualCode = manualResult.get(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      SystemRole tokenRoleAfter = tokenResult.get(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

      SystemRole manualStored = storedRole(manualAdmin);
      assertThat(List.of(manualStored, storedRole(tokenAdmin)))
          .containsExactlyInAnyOrder(SystemRole.SYSTEM_ADMIN, SystemRole.USER);
      // whichever way lost the race is the one whose administrator kept the role
      if (manualStored == SystemRole.SYSTEM_ADMIN) {
        assertThat(manualCode).isEqualTo(LocalAdminAvailabilityGuard.ERROR_CODE);
        assertThat(tokenRoleAfter).isEqualTo(SystemRole.USER);
      } else {
        assertThat(manualCode).isNull();
        assertThat(tokenRoleAfter).isEqualTo(SystemRole.SYSTEM_ADMIN);
      }
      assertThat(guard.countLoginCapableSystemAdmins(organizationId)).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Waits until both withdrawals have asked for the lock this test holds. The timeout only bounds
   * how long that state is waited for; a withdrawal that never asks makes the count stall below two
   * and the assertion names what was actually seen.
   */
  private void awaitBothWaitingForTheLock() {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(COMPLETION_TIMEOUT_SECONDS);
    long waiting = waitingForRoleChangeLock();
    while (waiting < WITHDRAWALS_IN_THE_RACE && System.nanoTime() < deadline) {
      sleepBriefly();
      waiting = waitingForRoleChangeLock();
    }
    assertThat(waiting)
        .as(
            "both withdrawals must run under the organization's advisory lock (ADR-0033,"
                + " Entscheidung 4) and therefore wait for it while this test holds it")
        .isEqualTo(WITHDRAWALS_IN_THE_RACE);
  }

  private static void sleepBriefly() {
    try {
      Thread.sleep(POLL_INTERVAL_MILLIS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for the lock waiters", e);
    }
  }
}
