package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * A sign-in that read the account before a concurrent act wrote it - a role withdrawn, a directory
 * lock set - writes back only the time of the sign-in and the provider's claims, never the rest of
 * the row it read. The interleaving is fixed: the act commits between the read and the write.
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

  @BeforeEach
  void rememberForeignUsers() {
    foreignUserIds = ownUserFixtures.existingUserIds();
  }

  @AfterEach
  void removeOwnUsers() {
    ownUserFixtures.removeUsersCreatedSince(foreignUserIds);
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
