package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnUserFixtures;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A sign-in that read the account before a concurrent act wrote it - a role withdrawn, a directory
 * lock set - writes back only the time of the sign-in and the provider's claims, never the rest of
 * the row it read. The interleaving is fixed: the act commits between the read and the write. Such
 * an act raises the version; the sign-in never fails on it, and its own writes leave the version
 * alone, so neither does an administrator's edit that read the account before the sign-in.
 */
@OpaaIntegrationTest
class UserServiceStaleRowIntegrationTest {

  private static final String ISSUER = "stale-row-issuer";

  @Autowired private UserService userService;
  @Autowired private UserRepository userRepository;
  @Autowired private InitialAdminPolicy initialAdminPolicy;
  @Autowired private OidcProviderRegistry providerRegistry;
  @Autowired private OidcProviderRepository providerRepository;
  @Autowired private TokenRoleSynchronizer roleSynchronizer;
  @Autowired private LocalAdminAvailabilityGuard adminGuard;
  @Autowired private LocalCredentialsRepository localCredentials;
  @Autowired private AuditorRoles auditorRoles;
  @Autowired private AuditorRoleRequestRepository auditorRoleRequests;
  @Autowired private AuditorCredentialRenewal credentialRenewal;
  @Autowired private PromotionSessionEnd promotionSessionEnd;
  @Autowired private AuditEventRecorder auditEventRecorder;
  @Autowired private ApplicationEventPublisher eventPublisher;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private Clock clock;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnUserFixtures ownUserFixtures;

  private Set<UUID> foreignUserIds;
  private UUID providerId;

  @BeforeEach
  void rememberForeignUsers() {
    foreignUserIds = ownUserFixtures.existingUserIds();
  }

  @AfterEach
  void removeOwnUsers() {
    ownUserFixtures.removeUsersCreatedSince(foreignUserIds);
    if (providerId != null) {
      jdbc.update("DELETE FROM oidc_providers WHERE id = ?", providerId);
    }
  }

  @Test
  void aSignInDoesNotFailOnAnAdministratorsEditCommittedAfterItsRead() {
    String subject = UUID.randomUUID().toString();
    User created = userService.findOrCreateUser(subject, ISSUER, "alt@stadt.example", "Alter Name");
    Instant longAgo = Instant.now().minus(30, ChronoUnit.DAYS);
    jdbc.update(
        "UPDATE users SET last_login_at = ? WHERE id = ?",
        Timestamp.from(longAgo),
        created.getId());
    UserRepository racing =
        racingAfterRead(
            "UPDATE users SET system_role = 'SYSTEM_ADMIN', version = version + 1 WHERE id = ?",
            created.getId());

    User signedIn =
        serviceOver(racing).findOrCreateUser(subject, ISSUER, "neu@stadt.example", "Neuer Name");

    assertThat(signedIn.getId()).isEqualTo(created.getId());
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT system_role, email, last_login_at FROM users WHERE id = ?", created.getId());
    assertThat(row.get("system_role")).isEqualTo("SYSTEM_ADMIN");
    assertThat(row.get("email")).isEqualTo("neu@stadt.example");
    assertThat(((Timestamp) row.get("last_login_at")).toInstant()).isAfter(longAgo);
  }

  @Test
  void anAdministratorsEditDoesNotFailOnASignInCommittedAfterItsRead() {
    String subject = UUID.randomUUID().toString();
    User created = userService.findOrCreateUser(subject, ISSUER, "alt@stadt.example", "Alter Name");
    jdbc.update(
        "UPDATE users SET last_login_at = ? WHERE id = ?",
        Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)),
        created.getId());

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              User read = userRepository.findById(created.getId()).orElseThrow();
              CompletableFuture.runAsync(
                      () ->
                          userService.findOrCreateUser(
                              subject, ISSUER, "neu@stadt.example", "Neuer Name"))
                  .join();
              read.setSystemRole(SystemRole.SYSTEM_ADMIN);
              userRepository.saveAndFlush(read);
            });

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT system_role, email, display_name FROM users WHERE id = ?", created.getId());
    assertThat(row.get("system_role")).isEqualTo("SYSTEM_ADMIN");
    assertThat(row.get("email")).isEqualTo("neu@stadt.example");
    assertThat(row.get("display_name")).isEqualTo("Neuer Name");
  }

  @Test
  void twoSignInsOfOneAccountApplyTheTokenRoleWithoutFailingEachOther() {
    OidcProvider provider =
        new OidcProvider(
            "Rollenprobe",
            "https://stale-row-" + UUID.randomUUID() + ".example/realms/opaa",
            "opaa",
            null,
            new OidcClaimMapping(null, null, "roles", "opaa-admin", "opaa-auditor", null));
    providerId = providerRepository.saveAndFlush(provider).getId();
    User created =
        userService.findOrCreateUser(
            UUID.randomUUID().toString(), provider.getIssuerUri(), "rolle@stadt.example", "Rolle");
    User firstRead = userRepository.findById(created.getId()).orElseThrow();
    User secondRead = userRepository.findById(created.getId()).orElseThrow();
    jdbc.update("UPDATE users SET version = version + 1 WHERE id = ?", created.getId());
    long versionBefore = versionOf(created.getId());

    User first =
        roleSynchronizer.apply(firstRead, provider, TokenRoles.named(List.of("opaa-admin")));
    User second =
        roleSynchronizer.apply(secondRead, provider, TokenRoles.named(List.of("opaa-admin")));

    assertThat(first.getSystemRole()).isEqualTo(SystemRole.SYSTEM_ADMIN);
    assertThat(second.getSystemRole()).isEqualTo(SystemRole.SYSTEM_ADMIN);
    assertThat(
            jdbc.queryForObject(
                "SELECT system_role FROM users WHERE id = ?", String.class, created.getId()))
        .isEqualTo("SYSTEM_ADMIN");
    assertThat(versionOf(created.getId()))
        .as("the role change raises the version, so an edit read before it fails")
        .isEqualTo(versionBefore + 1);
  }

  private long versionOf(UUID userId) {
    return jdbc.queryForObject("SELECT version FROM users WHERE id = ?", Long.class, userId);
  }

  /** The real repository; right after the sign-in's read, {@code sql} commits for {@code id}. */
  private UserRepository racingAfterRead(String sql, UUID id) {
    return (UserRepository)
        Proxy.newProxyInstance(
            UserRepository.class.getClassLoader(),
            new Class<?>[] {UserRepository.class},
            (proxy, method, args) -> {
              Object result = invoke(method, args);
              if (method.getName().equals("findBySubjectAndIssuer")) {
                jdbc.update(sql, id);
              }
              return result;
            });
  }

  @Test
  void aSignInDoesNotUndoARoleWithdrawalOrDirectoryLockCommittedAfterItsRead() {
    String subject = UUID.randomUUID().toString();
    User created = userService.findOrCreateUser(subject, ISSUER, "alt@stadt.example", "Alter Name");
    Instant longAgo = Instant.now().minus(30, ChronoUnit.DAYS);
    jdbc.update(
        "UPDATE users SET system_role = 'SYSTEM_ADMIN', last_login_at = ? WHERE id = ?",
        Timestamp.from(longAgo),
        created.getId());

    // delegates every call to the real repository; the read is followed by the concurrent act
    UserRepository racing =
        (UserRepository)
            Proxy.newProxyInstance(
                UserRepository.class.getClassLoader(),
                new Class<?>[] {UserRepository.class},
                (proxy, method, args) -> {
                  Object result = invoke(method, args);
                  if (method.getName().equals("findBySubjectAndIssuer")) {
                    // the concurrent act commits after the sign-in read the row
                    jdbc.update(
                        "UPDATE users SET system_role = 'USER', directory_locked_at = ?"
                            + " WHERE id = ?",
                        Timestamp.from(Instant.now()),
                        created.getId());
                  }
                  return result;
                });

    serviceOver(racing).findOrCreateUser(subject, ISSUER, "neu@stadt.example", "Neuer Name");

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT system_role, directory_locked_at, email, display_name, last_login_at"
                + " FROM users WHERE id = ?",
            created.getId());
    assertThat(row.get("system_role")).isEqualTo("USER");
    assertThat(row.get("directory_locked_at")).isNotNull();
    assertThat(row.get("email")).isEqualTo("neu@stadt.example");
    assertThat(row.get("display_name")).isEqualTo("Neuer Name");
    assertThat(((Timestamp) row.get("last_login_at")).toInstant()).isAfter(longAgo);
  }

  private Object invoke(Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(userRepository, args);
    } catch (InvocationTargetException failed) {
      throw failed.getCause();
    }
  }

  private UserService serviceOver(UserRepository repository) {
    return new UserService(
        repository,
        initialAdminPolicy,
        providerRegistry,
        providerRepository,
        roleSynchronizer,
        adminGuard,
        localCredentials,
        auditorRoles,
        auditorRoleRequests,
        credentialRenewal,
        promotionSessionEnd,
        auditEventRecorder,
        eventPublisher,
        transactionManager,
        clock);
  }
}
