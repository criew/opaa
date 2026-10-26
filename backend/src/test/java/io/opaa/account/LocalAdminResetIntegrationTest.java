package io.opaa.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.account.LocalAdminReset.Outcome;
import io.opaa.api.types.LocalAccountState;
import io.opaa.api.types.LockReason;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.AuthProperties;
import io.opaa.auth.LocalCredentials;
import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.OidcProviderRepository;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.organization.Organization;
import io.opaa.security.PasswordGenerator;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAdminRestrictionTest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link LocalAdminReset} against Postgres and the production {@code oidc} filter chain (ADR-0033,
 * Entscheidungen 5 and 9): {@code OPAA_LOCAL_ADMIN_RESET=force} restores the emergency account of
 * the installation once, with a password that stands in exactly one log event; a further start with
 * the variable resets nothing until a start without it; without an emergency account it creates one
 * under {@code OPAA_LOCAL_ADMIN_RESET_EMAIL} (ADR-0060, Entscheidung 8), never a second; and a
 * local {@code SYSTEM_ADMIN} signs in only from the allowed networks.
 */
@OpaaLocalAdminRestrictionTest
class LocalAdminResetIntegrationTest {

  private static final String EMAIL = "systemverwaltung@opaa.test.example";
  private static final String RESET_EMAIL = "notanker@opaa.test.example";
  private static final String LOGIN = "/api/v1/auth/local/login";
  private static final Pattern PASSWORD_LINE = Pattern.compile("Passwort:\\s+(\\S+)");

  @Autowired private MockMvc mockMvc;
  @Autowired private LocalAdminReset reset;
  @Autowired private LocalCredentialsRepository credentials;
  @Autowired private LocalRefreshTokenRepository refreshTokens;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private AuthProperties authProperties;
  @Autowired private LocalAuthProperties localAuthProperties;
  @Autowired private UserRepository users;
  @Autowired private OidcProviderRepository providers;
  @Autowired private InitialAdministrator initialAdministrator;
  @Autowired private LocalAdminResetRecordRepository resetRecord;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private PasswordGenerator passwordGenerator;
  @Autowired private AuditEventRecorder audit;
  @Autowired private ApplicationEventPublisher events;
  @Autowired private Clock clock;
  @Autowired private PlatformTransactionManager transactionManager;

  private LocalAccountFixtures fixtures;
  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void setUp() {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    jdbc.update("DELETE FROM local_admin_seed_marker");
    removeAuditRows();
    logs = new ListAppender<>();
    logs.start();
    rootLogger().addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    rootLogger().detachAppender(logs);
    fixtures.cleanUp();
    removeAuditRows();
  }

  /** The protocol of this class: what the reset and the sign-ins record in the default org. */
  private void removeAuditRows() {
    jdbc.update(
        "DELETE FROM audit_log WHERE organization_id = ? AND event_type IN"
            + " ('LOCAL_ADMIN_RESET', 'LOCAL_BOOTSTRAP_ACCOUNT_LOGIN')",
        Organization.DEFAULT_ID);
  }

  private static Logger rootLogger() {
    return (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
  }

  /** The emergency account as the installation leaves it: live, with a known password. */
  private LocalAccount emergencyAccount() {
    fixtures.localProvider(false);
    LocalAccount account =
        fixtures.account(EMAIL, SystemRole.SYSTEM_ADMIN, LocalAccountFixtures.PASSWORD, true, null);
    LocalCredentials row = fixtures.credentialsOf(account);
    row.markBootstrap();
    fixtures.save(row);
    return account;
  }

  private LocalCredentials bootstrapRow() {
    return credentials.findByBootstrapTrue().orElseThrow();
  }

  private ResultActions login(String email, String password, String remoteAddress)
      throws Exception {
    MockHttpServletRequestBuilder request =
        post(LOGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    if (remoteAddress != null) {
      request.with(
          servletRequest -> {
            servletRequest.setRemoteAddr(remoteAddress);
            return servletRequest;
          });
    }
    return mockMvc.perform(request);
  }

  /**
   * The reset of a start with {@code OPAA_LOCAL_ADMIN_RESET_EMAIL} set to {@code email}, in its own
   * transaction like the start-up's proxy.
   */
  private Outcome resetWithEmail(String email) {
    LocalAuthProperties withEmail =
        new LocalAuthProperties(
            localAuthProperties.jwtSecret(),
            localAuthProperties.accessTokenTtl(),
            localAuthProperties.refreshTokenTtl(),
            localAuthProperties.sessionMaxLifetime(),
            localAuthProperties.adminRefreshTokenTtl(),
            localAuthProperties.adminSessionMaxLifetime(),
            localAuthProperties.cookieSecure(),
            LocalAuthProperties.ADMIN_RESET_FORCE,
            email,
            localAuthProperties.adminAllowedCidrs(),
            localAuthProperties.refreshGracePeriod());
    LocalAdminReset configured =
        new LocalAdminReset(
            authProperties,
            withEmail,
            users,
            credentials,
            refreshTokens,
            providers,
            initialAdministrator,
            resetRecord,
            passwordEncoder,
            passwordGenerator,
            audit,
            events,
            clock);
    return new TransactionTemplate(transactionManager).execute(status -> configured.reset());
  }

  private long recreatedAudits() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE organization_id = ? AND event_type ="
            + " 'LOCAL_ADMIN_RESET' AND after LIKE '%\"recreated\":true%'",
        Long.class, Organization.DEFAULT_ID);
  }

  private long resetAudits() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE organization_id = ? AND event_type ="
            + " 'LOCAL_ADMIN_RESET'",
        Long.class,
        Organization.DEFAULT_ID);
  }

  private List<String> passwordsInLogs() {
    return logs.list.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .map(PASSWORD_LINE::matcher)
        .filter(Matcher::find)
        .map(matcher -> matcher.group(1))
        .toList();
  }

  @Test
  void theForcedRestartRestoresTheAccountAndEndsItsSessions() throws Exception {
    LocalAccount admin = emergencyAccount();
    login(EMAIL, LocalAccountFixtures.PASSWORD, null).andExpect(status().isOk());
    UUID userId = admin.id();
    assertThat(refreshTokens.findAll().stream().filter(t -> t.getUserId().equals(userId)))
        .isNotEmpty();
    LocalCredentials row = bootstrapRow();
    row.lock(LockReason.ADMIN, Instant.now(), null);
    row.setExpiresAt(Instant.now().minusSeconds(60), Instant.now());
    credentials.save(row);
    assertThat(bootstrapRow().state(Instant.now())).isEqualTo(LocalAccountState.LOCKED);

    assertThat(reset.reset()).isEqualTo(Outcome.RESET);

    LocalCredentials restored = bootstrapRow();
    assertThat(restored.getUserId()).isEqualTo(userId);
    assertThat(restored.state(Instant.now())).isEqualTo(LocalAccountState.ACTIVE);
    assertThat(restored.getExpiresAt()).isNull();
    assertThat(restored.isPasswordChangeRequired()).isTrue();
    assertThat(refreshTokens.findAll().stream().filter(t -> t.getUserId().equals(userId)))
        .allMatch(t -> t.getRevokedAt() != null);
    assertThat(resetAudits()).isOne();
    List<String> printed = passwordsInLogs();
    assertThat(printed).hasSize(1);
    login(EMAIL, LocalAccountFixtures.PASSWORD, null).andExpect(status().isUnauthorized());
    login(EMAIL, printed.getFirst(), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.passwordChangeRequired").value(true));
  }

  @Test
  void withoutAnEmergencyAccountAndWithoutAnAddressNothingIsCreated() {
    assertThat(reset.reset()).isEqualTo(Outcome.REJECTED);

    assertThat(credentials.findByBootstrapTrue()).isEmpty();
    assertThat(passwordsInLogs()).isEmpty();
    assertThat(resetAudits()).isZero();
    assertThat(logs.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .anyMatch(message -> message.contains(LocalAuthProperties.ADMIN_RESET_EMAIL_VARIABLE));
  }

  @Test
  void withoutAnEmergencyAccountTheResetCreatesOneUnderTheConfiguredAddress() throws Exception {
    assertThat(resetWithEmail(RESET_EMAIL)).isEqualTo(Outcome.CREATED);

    LocalCredentials row = bootstrapRow();
    User admin = users.findById(row.getUserId()).orElseThrow();
    assertThat(admin.getEmail()).isEqualTo(RESET_EMAIL);
    assertThat(admin.getSystemRole()).isEqualTo(SystemRole.SYSTEM_ADMIN);
    assertThat(row.isPasswordChangeRequired()).isTrue();
    assertThat(providers.findLocalRow()).isPresent();
    assertThat(recreatedAudits()).isOne();
    List<String> printed = passwordsInLogs();
    assertThat(printed).hasSize(1);
    assertThat(logs.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .noneMatch(message -> message.contains(RESET_EMAIL));
    fixtures.localProvider(true);
    login(RESET_EMAIL, printed.getFirst(), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.passwordChangeRequired").value(true));
  }

  @Test
  void anExistingEmergencyAccountIsResetAndNoSecondOneCreated() {
    emergencyAccount();

    assertThat(resetWithEmail(RESET_EMAIL)).isEqualTo(Outcome.RESET);

    assertThat(credentials.findAll().stream().filter(LocalCredentials::isBootstrap)).hasSize(1);
    assertThat(users.findByIssuerAndEmailIgnoreCase(LocalIssuer.URN, RESET_EMAIL)).isEmpty();
    assertThat(recreatedAudits()).isZero();
  }

  @Test
  void anUnusableAddressCreatesNothing() {
    fixtures.activeUser(RESET_EMAIL);

    assertThat(resetWithEmail(InitialAdministrator.REJECTED_DEFAULT_EMAIL))
        .isEqualTo(Outcome.REJECTED);
    assertThat(resetWithEmail("kein-postfach")).isEqualTo(Outcome.REJECTED);
    assertThat(resetWithEmail(RESET_EMAIL)).isEqualTo(Outcome.REJECTED);

    assertThat(credentials.findByBootstrapTrue()).isEmpty();
    assertThat(passwordsInLogs()).isEmpty();
  }

  @Test
  void twoInstancesCreatingAtOnceCreateTheAccountExactlyOnce() throws Exception {
    CyclicBarrier together = new CyclicBarrier(2);
    ExecutorService instances = Executors.newFixedThreadPool(2);
    List<String> outcomes = new ArrayList<>();
    try {
      List<Future<Outcome>> starts = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        starts.add(
            instances.submit(
                () -> {
                  together.await();
                  return resetWithEmail(RESET_EMAIL);
                }));
      }
      for (Future<Outcome> pending : starts) {
        outcomes.add(pending.get(60, TimeUnit.SECONDS).name());
      }
    } finally {
      instances.shutdownNow();
    }

    assertThat(outcomes).containsExactlyInAnyOrder("CREATED", "RESET_ALREADY_DONE");
    assertThat(credentials.findAll().stream().filter(LocalCredentials::isBootstrap)).hasSize(1);
    assertThat(recreatedAudits()).isOne();
    assertThat(passwordsInLogs()).hasSize(1);
  }

  @Test
  void aStartWithoutTheVariableWarnsWhenTheInstallationHasNoEmergencyAccount() {
    assertThat(reset.resetIfForced()).isEqualTo(Outcome.SKIPPED);

    assertThat(logs.list)
        .filteredOn(event -> event.getLevel() == Level.WARN)
        .extracting(ILoggingEvent::getFormattedMessage)
        .anyMatch(message -> message.contains("kein Notanker-Konto"));
  }

  /**
   * Two instances starting together with {@code OPAA_LOCAL_ADMIN_RESET=force}: exactly one resets
   * the account, the other finds the reset done, and the one password in the log signs in.
   */
  // regression guard for #2944: concurrent resets failed on @Version and logged a dead password
  @Test
  void twoInstancesResettingAtOnceResetTheAccountExactlyOnce() throws Exception {
    emergencyAccount();
    CyclicBarrier together = new CyclicBarrier(2);
    ExecutorService instances = Executors.newFixedThreadPool(2);
    List<String> outcomes = new ArrayList<>();
    try {
      List<Future<Outcome>> resets = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        resets.add(
            instances.submit(
                () -> {
                  together.await();
                  return reset.reset();
                }));
      }
      for (Future<Outcome> pending : resets) {
        outcomes.add(pending.get(60, TimeUnit.SECONDS).name());
      }
    } finally {
      instances.shutdownNow();
    }

    assertThat(outcomes).containsExactlyInAnyOrder("RESET", "RESET_ALREADY_DONE");
    assertThat(resetAudits()).isOne();
    assertThat(passwordsInLogs()).hasSize(1);
    login(EMAIL, passwordsInLogs().getFirst(), null).andExpect(status().isOk());
  }

  @Test
  void aFurtherStartWithTheVariableResetsNothingUntilAStartWithoutIt() throws Exception {
    emergencyAccount();
    assertThat(reset.reset()).isEqualTo(Outcome.RESET);
    String password = passwordsInLogs().getFirst();
    logs.list.clear();

    assertThat(reset.reset()).isEqualTo(Outcome.RESET_ALREADY_DONE);
    assertThat(passwordsInLogs()).isEmpty();
    login(EMAIL, password, null).andExpect(status().isOk());

    reset.releaseNextReset();
    assertThat(reset.reset()).isEqualTo(Outcome.RESET);
    assertThat(resetAudits()).isEqualTo(2);
  }

  @Test
  void aLocalSystemAdminSignsInOnlyFromTheAllowedNetworksWithTheSameRefusalAsAWrongPassword()
      throws Exception {
    emergencyAccount();
    fixtures.localProvider(true);
    LocalAccount regular = fixtures.activeUser("erika-" + UUID.randomUUID() + "@stadt.example");

    login(EMAIL, LocalAccountFixtures.PASSWORD, "10.20.30.40").andExpect(status().isOk());
    String refusedNetwork =
        login(EMAIL, LocalAccountFixtures.PASSWORD, "192.168.7.7")
            .andExpect(status().isUnauthorized())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String wrongPassword =
        login(EMAIL, "falsches-passwort", "10.20.30.40")
            .andExpect(status().isUnauthorized())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(stripTimestamp(refusedNetwork)).isEqualTo(stripTimestamp(wrongPassword));
    // regular local accounts are not restricted
    login(regular.email(), LocalAccountFixtures.PASSWORD, "192.168.7.7").andExpect(status().isOk());
    // a refused network is no failed password: the counter stays untouched
    assertThat(bootstrapRow().getFailedLoginAttempts()).isEqualTo(1);
  }

  private static String stripTimestamp(String body) {
    return body.replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"-\"");
  }
}
