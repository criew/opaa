package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.LockReason;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.AuthProperties;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.common.ConflictException;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link LocalAdminAvailabilityGuard} against a real Postgres (ADR-0033, Entscheidung 4): the one
 * place that counts <em>login-capable</em> system administrators - a local {@code ACTIVE} account
 * with a password, an account of an enabled OIDC provider, or (in this context's {@code dev} mode)
 * an account of the dev issuer - and refuses the change that would remove the last one, also when
 * two withdrawals race each other on two real connections.
 */
@OpaaIntegrationTest
class LocalAdminAvailabilityGuardIntegrationTest {

  private static final String ENABLED_ISSUER = "https://idp.example/realms/guard-enabled";
  private static final String DISABLED_ISSUER = "https://idp.example/realms/guard-disabled";
  private static final String UNKNOWN_ISSUER = "https://idp.example/realms/guard-unknown";

  @Autowired private LocalAdminAvailabilityGuard guard;
  @Autowired private UserRepository users;
  @Autowired private LocalCredentialsRepository credentials;
  @Autowired private OidcProviderRepository providers;
  @Autowired private OrganizationRepository organizations;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private AuthProperties authProperties;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;

  private UUID organizationId;
  private OidcProvider enabledProvider;
  private OidcProvider disabledProvider;
  private final List<User> created = new ArrayList<>();

  @BeforeEach
  void setUp() {
    organizationId = organizations.save(new Organization(UUID.randomUUID(), "Guard")).getId();
    enabledProvider = providers.save(provider("Aktiv", ENABLED_ISSUER, true));
    disabledProvider = providers.save(provider("Inaktiv", DISABLED_ISSUER, false));
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    for (User user : created) {
      credentials.deleteById(user.getId());
      users.deleteById(user.getId());
    }
    providers.delete(enabledProvider);
    providers.delete(disabledProvider);
    organizations.deleteById(organizationId);
  }

  private static OidcProvider provider(String name, String issuer, boolean enabled) {
    OidcProvider provider =
        new OidcProvider(name, issuer, "opaa-frontend", null, OidcClaimMapping.keycloakDefaults());
    if (!enabled) {
      provider.disable();
    }
    return provider;
  }

  private User oidcAdmin(String issuer) {
    User user = new User("sub-" + UUID.randomUUID(), issuer, null, "OIDC");
    user.setOrganizationId(organizationId);
    user.setSystemRole(SystemRole.SYSTEM_ADMIN);
    created.add(user);
    return users.save(user);
  }

  private User localAccount(SystemRole role, boolean withPassword) {
    User user = User.localAccount("guard-" + UUID.randomUUID() + "@stadt.example", "Lokal");
    user.setOrganizationId(organizationId);
    user.setSystemRole(role);
    User saved = users.save(user);
    created.add(saved);
    Instant now = Instant.now();
    LocalCredentials row = new LocalCredentials(saved.getId(), "Testkonto", now);
    row.markEmailVerified(now);
    if (withPassword) {
      row.setPasswordHash(passwordEncoder.encode("irrelevant"), now);
    }
    credentials.save(row);
    return saved;
  }

  private LocalCredentials rowOf(User user) {
    return credentials.findById(user.getId()).orElseThrow();
  }

  private SystemRole storedRole(User user) {
    return users.findById(user.getId()).orElseThrow().getSystemRole();
  }

  /** The {@code require…} checks demand the caller's transaction (the lock must hold to commit). */
  private void inTransaction(Runnable action) {
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
  }

  @Test
  void countsOnlyLoginCapableSystemAdministratorsOfTheOrganization() {
    assertThat(guard.countLoginCapableSystemAdmins(organizationId)).isZero();

    localAccount(SystemRole.SYSTEM_ADMIN, false); // INVITED: no password
    localAccount(SystemRole.USER, true); // not an administrator
    oidcAdmin(DISABLED_ISSUER); // provider switched off
    oidcAdmin(UNKNOWN_ISSUER); // no provider row at all
    assertThat(guard.countLoginCapableSystemAdmins(organizationId)).isZero();

    User locked = localAccount(SystemRole.SYSTEM_ADMIN, true);
    LocalCredentials lockedRow = rowOf(locked);
    lockedRow.lock(LockReason.ADMIN, Instant.now(), null);
    credentials.save(lockedRow);
    User expired = localAccount(SystemRole.SYSTEM_ADMIN, true);
    LocalCredentials expiredRow = rowOf(expired);
    expiredRow.setExpiresAt(Instant.now().minus(Duration.ofDays(1)), Instant.now());
    credentials.save(expiredRow);
    User lockedOut = localAccount(SystemRole.SYSTEM_ADMIN, true);
    LocalCredentials lockedOutRow = rowOf(lockedOut);
    lockedOutRow.recordLockoutUntil(Instant.now().plus(Duration.ofMinutes(10)), Instant.now());
    credentials.save(lockedOutRow);
    assertThat(guard.countLoginCapableSystemAdmins(organizationId)).isZero();

    localAccount(SystemRole.SYSTEM_ADMIN, true);
    assertThat(guard.countLoginCapableSystemAdmins(organizationId)).isEqualTo(1);
    oidcAdmin(ENABLED_ISSUER);
    assertThat(guard.countLoginCapableSystemAdmins(organizationId)).isEqualTo(2);
    // the dev issuer is the trusted provider of the dev mode (ADR-0005): its accounts count
    oidcAdmin(authProperties.dev().issuer());
    assertThat(guard.countLoginCapableSystemAdmins(organizationId)).isEqualTo(3);
    // an expired lockout is over: the account is login capable again
    lockedOutRow = rowOf(lockedOut);
    lockedOutRow.recordLockoutUntil(Instant.now().minusSeconds(1), Instant.now().minusSeconds(2));
    credentials.save(lockedOutRow);
    assertThat(guard.countLoginCapableSystemAdmins(organizationId)).isEqualTo(4);
  }

  @Test
  void anAdministratorOfAnotherOrganizationDoesNotCount() {
    UUID other = organizations.save(new Organization(UUID.randomUUID(), "Andere")).getId();
    User elsewhere = new User("fremd-" + UUID.randomUUID(), ENABLED_ISSUER, null, null);
    elsewhere.setOrganizationId(other);
    elsewhere.setSystemRole(SystemRole.SYSTEM_ADMIN);
    users.save(elsewhere);
    try {
      assertThat(guard.countLoginCapableSystemAdmins(organizationId)).isZero();
    } finally {
      users.delete(elsewhere);
      organizations.deleteById(other);
    }
  }

  @Test
  void theLastLoginCapableAdministratorKeepsTheRole() {
    User active = localAccount(SystemRole.SYSTEM_ADMIN, true);
    User locked = localAccount(SystemRole.SYSTEM_ADMIN, true);
    LocalCredentials lockedRow = rowOf(locked);
    lockedRow.lock(LockReason.ADMIN, Instant.now(), null);
    credentials.save(lockedRow);

    assertThat(guard.withdrawSystemAdminIfAnotherRemains(active, SystemRole.USER)).isZero();
    assertThat(storedRole(active)).isEqualTo(SystemRole.SYSTEM_ADMIN);
    assertThatThrownBy(
            () ->
                inTransaction(
                    () -> guard.requireAnotherLoginCapableAdmin(organizationId, active.getId())))
        .isInstanceOf(ConflictException.class)
        .satisfies(
            e ->
                assertThat(((ConflictException) e).getCode())
                    .isEqualTo(LocalAdminAvailabilityGuard.ERROR_CODE))
        .hasMessageContaining("Systemverwalter");

    lockedRow = rowOf(locked);
    lockedRow.unlock(Instant.now());
    credentials.save(lockedRow);
    assertThatCode(
            () ->
                inTransaction(
                    () -> guard.requireAnotherLoginCapableAdmin(organizationId, active.getId())))
        .doesNotThrowAnyException();
    // without a surrounding transaction the check refuses to run at all (MANDATORY)
    assertThatThrownBy(() -> guard.requireAnotherLoginCapableAdmin(organizationId, active.getId()))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThat(guard.withdrawSystemAdminIfAnotherRemains(active, SystemRole.USER)).isEqualTo(1);
    assertThat(storedRole(active)).isEqualTo(SystemRole.USER);
    // the withdrawal of a role the account no longer holds writes nothing
    assertThat(guard.withdrawSystemAdminIfAnotherRemains(active, SystemRole.USER)).isZero();
  }

  @Test
  void disablingTheProviderOfTheOnlyAdministratorNeedsALocalOne() {
    oidcAdmin(ENABLED_ISSUER);

    assertThatThrownBy(
            () ->
                inTransaction(
                    () ->
                        guard.requireLoginCapableAdminWithoutProvider(
                            organizationId, enabledProvider.getId())))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("lokales Systemverwalterkonto mit Passwort");
    // excluding a provider whose administrators do not count anyway leaves the enabled one
    assertThatCode(
            () ->
                inTransaction(
                    () ->
                        guard.requireLoginCapableAdminWithoutProvider(
                            organizationId, disabledProvider.getId())))
        .doesNotThrowAnyException();

    localAccount(SystemRole.SYSTEM_ADMIN, true);
    assertThatCode(
            () ->
                inTransaction(
                    () ->
                        guard.requireLoginCapableAdminWithoutProvider(
                            organizationId, enabledProvider.getId())))
        .doesNotThrowAnyException();
  }

  /**
   * Two withdrawals on two real connections: the advisory lock serializes them, so the second one
   * counts the first one's row as gone and is refused - never both succeed.
   */
  @Test
  void twoConcurrentWithdrawalsLeaveExactlyOneAdministrator() throws Exception {
    User first = localAccount(SystemRole.SYSTEM_ADMIN, true);
    User second = localAccount(SystemRole.SYSTEM_ADMIN, true);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Integer>> results =
          List.of(
              pool.submit(
                  () -> {
                    start.await();
                    return guard.withdrawSystemAdminIfAnotherRemains(first, SystemRole.USER);
                  }),
              pool.submit(
                  () -> {
                    start.await();
                    return guard.withdrawSystemAdminIfAnotherRemains(second, SystemRole.USER);
                  }));
      start.countDown();
      int written = 0;
      for (Future<Integer> result : results) {
        written += result.get(30, TimeUnit.SECONDS);
      }
      assertThat(written).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
    List<SystemRole> remaining = List.of(storedRole(first), storedRole(second));
    assertThat(remaining).containsExactlyInAnyOrder(SystemRole.SYSTEM_ADMIN, SystemRole.USER);
    assertThat(guard.countLoginCapableSystemAdmins(organizationId)).isEqualTo(1);
  }
}
