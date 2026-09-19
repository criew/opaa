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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
   * How long a withdrawal blocked on the advisory lock is given to prove that it is blocked. One
   * that never takes the lock returns within milliseconds, so this only bounds the waiting time.
   */
  private static final long BLOCKED_GRACE_MILLIS = 1_000;

  private static final long COMPLETION_TIMEOUT_SECONDS = 30;

  @Autowired private UserService userService;
  @Autowired private TokenRoleSynchronizer synchronizer;
  @Autowired private LocalAdminAvailabilityGuard guard;
  @Autowired private UserRepository users;
  @Autowired private OidcProviderRepository providers;
  @Autowired private OrganizationRepository organizations;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;

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
        providers.save(
            provider(
                "Beschäftigte",
                new OidcClaimMapping(
                    null, null, "realm_access.roles", "opaa-admin", "opaa-auditor", null)));
    manualProvider = providers.save(provider("Gäste", OidcClaimMapping.keycloakDefaults()));
    tokenAdmin = admin(tokenProvider, "token");
    manualAdmin = admin(manualProvider, "manuell");
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    users.deleteAll(List.of(tokenAdmin, manualAdmin));
    providers.deleteAll(List.of(tokenProvider, manualProvider));
    organizations.deleteById(organizationId);
  }

  private static OidcProvider provider(String displayName, OidcClaimMapping mapping) {
    return new OidcProvider(
        displayName,
        "https://idp.example/realms/withdrawal-" + UUID.randomUUID(),
        "opaa-frontend",
        null,
        mapping);
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
    return users.save(user);
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
   * transaction of this test holds the organization's advisory lock while both run, so neither may
   * get past its check before the lock is free. A withdrawal that does not take the lock commits
   * during the barrier - and two such commits are what leave no administrator behind.
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
                assertThat(
                        catchThrowable(
                            () -> manualResult.get(BLOCKED_GRACE_MILLIS, TimeUnit.MILLISECONDS)))
                    .as(
                        "the manual withdrawal must run under the organization's advisory lock"
                            + " (ADR-0033, Entscheidung 4) and therefore block while this test"
                            + " holds it")
                    .isInstanceOf(TimeoutException.class);
                assertThat(
                        catchThrowable(
                            () -> tokenResult.get(BLOCKED_GRACE_MILLIS, TimeUnit.MILLISECONDS)))
                    .as("the token withdrawal must block on the same lock")
                    .isInstanceOf(TimeoutException.class);
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
}
