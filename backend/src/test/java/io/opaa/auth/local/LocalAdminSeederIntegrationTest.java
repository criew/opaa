package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import io.opaa.api.types.LocalAccountState;
import io.opaa.api.types.LockReason;
import io.opaa.api.types.PasswordChangeReason;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.local.LocalAdminSeeder.Outcome;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.organization.Organization;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthMockMvcTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@link LocalAdminSeeder} against a fresh Postgres and the production {@code oidc} filter chain
 * (ADR-0033, Entscheidung 5): the first start creates the LOCAL row and a live bootstrap
 * administrator whose sign-in with the one-time password from the log succeeds and demands a
 * password change; a second start seeds nothing even after the account was deleted; the forced
 * restart restores it; an existing installation gets an {@code INVITED} account; the password
 * stands in exactly one log line and in no audit row; the bootstrap sign-in is audited; and a local
 * {@code SYSTEM_ADMIN} signs in only from the allowed networks (Entscheidung 9).
 */
// Own context (AGENTS.md, "Spring-Testkontexte"): the shared oidc context runs with the shipped
// default address, which the seeder rejects on purpose; this class needs a deliverable address and
// the admin network restriction, both constant properties of this class alone.
@OpaaLocalAuthMockMvcTest
@TestPropertySource(
    properties = {
      "opaa.auth.initial-admin-email=" + LocalAdminSeederIntegrationTest.EMAIL,
      "opaa.auth.local.admin-allowed-cidrs=127.0.0.1/32,10.0.0.0/8"
    })
class LocalAdminSeederIntegrationTest {

  static final String EMAIL = "it-postfach@stadt.example";
  private static final String LOGIN = "/api/v1/auth/local/login";
  private static final String ME = "/api/v1/auth/me";
  private static final Pattern PASSWORD_LINE = Pattern.compile("Passwort:\\s+(\\S+)");

  @Autowired private MockMvc mockMvc;
  @Autowired private LocalAdminSeeder seeder;
  @Autowired private LocalAdminSeedMarkerRepository marker;
  @Autowired private UserRepository users;
  @Autowired private LocalCredentialsRepository credentials;
  @Autowired private LocalRefreshTokenRepository refreshTokens;
  @Autowired private OidcProviderRepository providers;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private JdbcTemplate jdbc;

  private LocalAccountFixtures fixtures;
  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void resetToAFreshInstallation() {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    jdbc.update("DELETE FROM users WHERE issuer = 'https://idp.example/realms/bestand'");
    jdbc.update("DELETE FROM local_admin_seed_marker");
    jdbc.update("DELETE FROM oidc_provider_seed_marker");
    jdbc.update("DELETE FROM audit_log");
    logs = new ListAppender<>();
    logs.start();
    rootLogger().addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    rootLogger().detachAppender(logs);
    fixtures.cleanUp();
  }

  private static Logger rootLogger() {
    return (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
  }

  private String seedFreshAndReadPassword() {
    assertThat(users.count()).as("a fresh installation has no account").isZero();
    assertThat(seeder.seedIfNeeded()).isEqualTo(Outcome.SEEDED_ACTIVE);
    List<String> withPassword =
        logs.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .filter(message -> PASSWORD_LINE.matcher(message).find())
            .toList();
    assertThat(withPassword).as("exactly one log event carries the password block").hasSize(1);
    Matcher matcher = PASSWORD_LINE.matcher(withPassword.getFirst());
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
  }

  private LocalCredentials bootstrapRow() {
    return credentials.findByBootstrapTrue().orElseThrow();
  }

  private User bootstrapUser() {
    return users.findById(bootstrapRow().getUserId()).orElseThrow();
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

  private List<String> auditTypes() {
    return jdbc.queryForList(
        "SELECT event_type FROM audit_log ORDER BY recorded_at, event_id", String.class);
  }

  @Test
  void theFirstStartSeedsTheLocalRowAndABootstrapAdminWhoseSignInDemandsAPasswordChange()
      throws Exception {
    String password = seedFreshAndReadPassword();

    Optional<OidcProvider> localRow = providers.findLocalRow();
    assertThat(localRow).isPresent();
    assertThat(localRow.get().isEnabled()).isFalse();
    assertThat(localRow.get().isDefaultProvider()).isFalse();
    assertThat(localRow.get().getIssuerUri()).isEqualTo(LocalIssuer.URN);
    assertThat(providers.findAll().stream().filter(OidcProvider::isLocal)).hasSize(1);

    User admin = bootstrapUser();
    assertThat(admin.getEmail()).isEqualTo(EMAIL);
    assertThat(admin.getIssuer()).isEqualTo(LocalIssuer.URN);
    assertThat(admin.getSubject()).isEqualTo(admin.getId().toString());
    assertThat(admin.getSystemRole()).isEqualTo(SystemRole.SYSTEM_ADMIN);
    assertThat(admin.getOrganizationId()).isEqualTo(Organization.DEFAULT_ID);
    LocalCredentials row = bootstrapRow();
    assertThat(row.isPasswordChangeRequired()).isTrue();
    assertThat(row.getPasswordChangeReason()).isEqualTo(PasswordChangeReason.INITIAL);
    assertThat(row.getCreatedReason()).isEqualTo(LocalAdminSeeder.CREATED_REASON);
    assertThat(marker.seedAlreadyAttempted()).isTrue();
    assertThat(auditTypes()).containsExactly("LOCAL_ADMIN_SEEDED");

    MvcResult signedIn =
        login(EMAIL, password, null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.passwordChangeRequired").value(true))
            .andExpect(jsonPath("$.passwordChangeReason").value("INITIAL"))
            .andReturn();
    String token = JsonPath.read(signedIn.getResponse().getContentAsString(), "$.accessToken");
    // the token opens nothing but the password change until the change is made
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
    // the one audited sign-in: the emergency account was used (ADR-0033, Entscheidung 13)
    assertThat(auditTypes()).containsExactly("LOCAL_ADMIN_SEEDED", "LOCAL_BOOTSTRAP_ACCOUNT_LOGIN");
    Map<String, Object> loginEvent =
        jdbc.queryForMap(
            "SELECT actor_kind, actor_ref FROM audit_log WHERE event_type = ?",
            "LOCAL_BOOTSTRAP_ACCOUNT_LOGIN");
    assertThat(loginEvent.get("actor_kind")).isEqualTo("SYSTEM_PROCESS");
    assertThat(loginEvent.get("actor_ref")).isEqualTo(LocalRefreshTokenService.SYSTEM_ACTOR);
  }

  @Test
  void thePasswordStandsInExactlyOneLogEventAndInNoAuditRow() throws Exception {
    String password = seedFreshAndReadPassword();
    login(EMAIL, password, null).andExpect(status().isOk());

    long eventsWithPassword =
        logs.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .filter(message -> message.contains(password))
            .count();
    assertThat(eventsWithPassword).isEqualTo(1);
    Integer auditRowsWithPassword =
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_log WHERE before LIKE ? OR after LIKE ? OR reason LIKE ?"
                + " OR object_label LIKE ?",
            Integer.class,
            "%" + password + "%",
            "%" + password + "%",
            "%" + password + "%",
            "%" + password + "%");
    assertThat(auditRowsWithPassword).isZero();
  }

  @Test
  void aSecondStartSeedsNothingEvenAfterTheAccountWasDeleted() {
    seedFreshAndReadPassword();

    assertThat(seeder.seedIfNeeded()).isEqualTo(Outcome.SKIPPED);
    users.delete(bootstrapUser());
    assertThat(credentials.findByBootstrapTrue()).isEmpty();

    assertThat(seeder.seedIfNeeded()).isEqualTo(Outcome.SKIPPED);
    assertThat(credentials.findByBootstrapTrue()).isEmpty();
    assertThat(auditTypes()).containsExactly("LOCAL_ADMIN_SEEDED");
  }

  @Test
  void theForcedRestartRestoresTheAccountEndsItsSessionsAndRecreatesADeletedOne() throws Exception {
    String password = seedFreshAndReadPassword();
    login(EMAIL, password, null).andExpect(status().isOk());
    UUID userId = bootstrapUser().getId();
    assertThat(refreshTokens.findAll().stream().filter(t -> t.getUserId().equals(userId)))
        .isNotEmpty();
    LocalCredentials row = bootstrapRow();
    row.lock(LockReason.ADMIN, Instant.now(), null);
    row.setExpiresAt(Instant.now().minusSeconds(60), Instant.now());
    credentials.save(row);
    assertThat(bootstrapRow().state(Instant.now())).isEqualTo(LocalAccountState.LOCKED);
    logs.list.clear();

    assertThat(seeder.restoreBootstrapAdmin()).isEqualTo(Outcome.RESET);

    LocalCredentials restored = bootstrapRow();
    assertThat(restored.getUserId()).isEqualTo(userId);
    assertThat(restored.state(Instant.now())).isEqualTo(LocalAccountState.ACTIVE);
    assertThat(restored.getExpiresAt()).isNull();
    assertThat(restored.isPasswordChangeRequired()).isTrue();
    assertThat(restored.getPasswordInvalidatedBefore()).isNotNull();
    assertThat(refreshTokens.findAll().stream().filter(t -> t.getUserId().equals(userId)))
        .allMatch(t -> t.getRevokedAt() != null);
    assertThat(auditTypes()).contains("LOCAL_ADMIN_RESET");
    String newPassword = passwordFromLogs();
    assertThat(newPassword).isNotEqualTo(password);
    login(EMAIL, password, null).andExpect(status().isUnauthorized());
    login(EMAIL, newPassword, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.passwordChangeRequired").value(true));

    users.delete(bootstrapUser());
    logs.list.clear();
    assertThat(seeder.restoreBootstrapAdmin()).isEqualTo(Outcome.RESET);
    assertThat(bootstrapUser().getEmail()).isEqualTo(EMAIL);
    assertThat(bootstrapUser().getSystemRole()).isEqualTo(SystemRole.SYSTEM_ADMIN);
    login(EMAIL, passwordFromLogs(), null).andExpect(status().isOk());
  }

  @Test
  void anExistingInstallationGetsAnInvitedAccountThatCannotSignIn() throws Exception {
    User existing =
        new User("bestand-" + UUID.randomUUID(), "https://idp.example/realms/bestand", null, null);
    existing.setOrganizationId(Organization.DEFAULT_ID);
    users.save(existing);

    assertThat(seeder.seedIfNeeded()).isEqualTo(Outcome.SEEDED_INVITED);

    LocalCredentials row = bootstrapRow();
    assertThat(row.getPasswordHash()).isNull();
    assertThat(row.state(Instant.now())).isEqualTo(LocalAccountState.INVITED);
    assertThat(marker.seedAlreadyAttempted()).isTrue();
    assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
        .noneMatch(message -> PASSWORD_LINE.matcher(message).find());
    login(EMAIL, "irgendwas", null).andExpect(status().isUnauthorized());
  }

  @Test
  void aLocalSystemAdminSignsInOnlyFromTheAllowedNetworksWithTheSameRefusalAsAWrongPassword()
      throws Exception {
    String password = seedFreshAndReadPassword();
    fixtures.localProvider(true);
    LocalAccount regular = fixtures.activeUser("erika-" + UUID.randomUUID() + "@stadt.example");

    login(EMAIL, password, "10.20.30.40").andExpect(status().isOk());
    String refusedNetwork =
        login(EMAIL, password, "192.168.7.7")
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
    assertThat(auditTypes()).doesNotContain("LOCAL_ACCOUNT_LOCKED_AFTER_FAILED_LOGINS");
  }

  private String passwordFromLogs() {
    return logs.list.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .map(PASSWORD_LINE::matcher)
        .filter(Matcher::find)
        .map(matcher -> matcher.group(1))
        .findFirst()
        .orElseThrow();
  }

  private static String stripTimestamp(String body) {
    return body.replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"-\"");
  }
}
