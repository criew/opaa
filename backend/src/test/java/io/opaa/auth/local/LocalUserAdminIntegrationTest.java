package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.jayway.jsonpath.JsonPath;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.LocalAccountState;
import io.opaa.api.types.MailEncryption;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ConflictException;
import io.opaa.group.Group;
import io.opaa.group.GroupMembershipHistory;
import io.opaa.group.GroupMembershipHistoryCause;
import io.opaa.group.GroupMembershipHistoryRepository;
import io.opaa.group.GroupRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.mail.MailSettingsService;
import io.opaa.mail.MailSettingsUpdate;
import io.opaa.mail.MailTestSupport;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthLinkTest;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The administration of local accounts through the production {@code oidc} chain (ADR-0033,
 * Entscheidungen 4, 11 and 13): invitation with mail and link fallback, generated initial password,
 * lock and unlock with immediate effect on running sessions, password reset by link and by
 * generated password, changes with the audit rule "before/after for the expiry only", deletion of
 * accounts without content, the guards around the caller and the last login-capable system
 * administrator - also under two concurrent requests - and a list that shows local accounts only.
 * Mail goes to an in-JVM GreenMail; every link path is proved by the message that arrived.
 */
@OpaaLocalAuthLinkTest
class LocalUserAdminIntegrationTest {

  private static final String LOGIN = "/api/v1/auth/local/login";
  private static final String ME = "/api/v1/auth/me";
  private static final String LOCAL_USERS = "/api/v1/admin/local-users";
  private static final Pattern TOKEN_IN_LINK = Pattern.compile("token=([A-Za-z0-9_-]+)");

  @Autowired private MockMvc mockMvc;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private LocalCredentialsRepository credentials;
  @Autowired private LocalActionTokenService actionTokens;
  @Autowired private LocalActionTokenRepository actionTokenRepository;
  @Autowired private LocalUserAdminService adminService;
  @Autowired private UserRepository users;
  @Autowired private KnowledgeLibraryRepository libraries;
  @Autowired private GroupRepository groups;
  @Autowired private GroupMembershipHistoryRepository groupHistory;
  @Autowired private OrganizationRepository organizations;
  @Autowired private MailSettingsService mailSettings;
  @Autowired private AuditEventRecorder audit;
  @Autowired private JdbcTemplate jdbc;

  private LocalAccountFixtures fixtures;
  private LocalAccount admin;
  private String adminBearer;
  private GreenMail greenMail;

  @BeforeEach
  void setUp() throws Exception {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    actionTokenRepository.deleteAll();
    deleteLocalAuditRows();
    fixtures.localProvider(true);
    admin = fixtures.activeAdmin("verwaltung-" + UUID.randomUUID() + "@stadt.example");
    adminBearer = bearer(login(admin.email(), LocalAccountFixtures.PASSWORD, 200));
    greenMail = new GreenMail(new ServerSetup(freePort(), "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
    greenMail.setUser("opaa@intern.example", "kennung", "geheim");
    greenMail.start();
    configureSmtp(true);
  }

  @AfterEach
  void tearDown() {
    greenMail.stop();
    configureSmtp(false);
    MailTestSupport.resetCaches(mailSettings);
    actionTokenRepository.deleteAll();
    deleteLocalAuditRows();
    fixtures.cleanUp();
  }

  @Test
  void anInvitationCreatesAnInvitedAccountMailsTheLinkAndAuditsTheDeliveryPath() throws Exception {
    String email = "erika-" + UUID.randomUUID() + "@stadt.example";
    Instant before = Instant.now();

    MvcResult created =
        asAdminJson(
                post(LOCAL_USERS),
                createBody(email, "Erika Muster", "INVITE", "Sachbearbeitung Bauamt, befristet"))
            .andReturn();
    // (MockMvc's fluent API is split so the created id can be read back)
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    String body = created.getResponse().getContentAsString();
    assertThat(JsonPath.<Boolean>read(body, "$.emailSent")).isTrue();
    assertThat(JsonPath.<String>read(body, "$.deliveryPath")).isEqualTo("MAIL_SENT");
    assertThat(JsonPath.<Object>read(body, "$.setupUrl")).isNull();
    assertThat(JsonPath.<Object>read(body, "$.initialPassword")).isNull();
    assertThat(JsonPath.<String>read(body, "$.user.status")).isEqualTo("INVITED");
    assertThat(JsonPath.<String>read(body, "$.user.activity")).isEqualTo("NEVER");
    assertThat(JsonPath.<String>read(body, "$.user.email")).isEqualTo(email);
    assertThat(JsonPath.<String>read(body, "$.user.createdReason"))
        .isEqualTo("Sachbearbeitung Bauamt, befristet");
    assertThat(JsonPath.<Boolean>read(body, "$.user.bootstrap")).isFalse();
    Instant expiresAt = Instant.parse(JsonPath.read(body, "$.user.expiresAt"));
    // the default expiry of 90 days is prefilled
    assertThat(expiresAt).isAfter(before.plus(Duration.ofDays(89)));
    assertThat(expiresAt).isBefore(before.plus(Duration.ofDays(91)));
    UUID id = UUID.fromString(JsonPath.read(body, "$.user.id"));

    assertThat(greenMail.waitForIncomingEmail(10_000, 1)).isTrue();
    MimeMessage mail = greenMail.getReceivedMessages()[0];
    assertThat(mail.getAllRecipients()[0].toString()).isEqualTo(email);
    String text = plainText(mail);
    assertThat(text).contains("https://opaa.test.example/" + LocalAccountLinks.SET_PASSWORD_PATH);
    String rawToken = tokenIn(text);

    // the link is a redeemable SET_PASSWORD token of exactly this account
    LocalActionToken token =
        actionTokens.findRedeemable(rawToken, ActionTokenPurpose.SET_PASSWORD).orElseThrow();
    assertThat(token.getUserId()).isEqualTo(id);
    assertThat(token.getExpiresAt())
        .isAfter(before.plus(Duration.ofHours(71)))
        .isBefore(before.plus(Duration.ofHours(73)));
    // the local half exists with the administrator vouching for the address
    LocalCredentials row = credentials.findById(id).orElseThrow();
    assertThat(row.getEmailVerifiedAt()).isNotNull();
    assertThat(row.getPasswordHash()).isNull();

    assertThat(auditRows("LOCAL_USER_CREATED", id)).hasSize(1);
    List<Map<String, Object>> invited = auditRows("LOCAL_USER_INVITED", id);
    assertThat(invited).hasSize(1);
    assertThat((String) invited.getFirst().get("after")).contains("MAIL_SENT");
    // no address, no name, no creation reason in either row
    for (Map<String, Object> event : auditRows("LOCAL_USER_%", id)) {
      assertThat(String.valueOf(event.values()))
          .doesNotContain(email)
          .doesNotContain("Erika Muster")
          .doesNotContain("Bauamt")
          .doesNotContain(id.toString());
    }
    // the account is listed and readable, but no later call yields the link again
    asAdmin(get(LOCAL_USERS + "/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("INVITED"))
        .andExpect(jsonPath("$.setupUrl").doesNotExist());
  }

  @Test
  void theLinkIsHandedOverExactlyOnceWhenTheMailServerRefusesOrSmtpIsOff() throws Exception {
    greenMail.stop();
    String email = "erika-" + UUID.randomUUID() + "@stadt.example";
    MvcResult failed =
        asAdminJson(post(LOCAL_USERS), createBody(email, "Erika Muster", "INVITE", "Vertretung"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.emailSent").value(false))
            .andExpect(jsonPath("$.deliveryPath").value("MAIL_FAILED"))
            .andReturn();
    String setupUrl = JsonPath.read(failed.getResponse().getContentAsString(), "$.setupUrl");
    assertThat(setupUrl).startsWith("https://opaa.test.example/");
    UUID id =
        UUID.fromString(JsonPath.read(failed.getResponse().getContentAsString(), "$.user.id"));
    String firstToken = tokenIn(setupUrl);
    assertThat(actionTokens.findRedeemable(firstToken, ActionTokenPurpose.SET_PASSWORD))
        .isPresent();
    assertThat((String) auditRows("LOCAL_USER_INVITED", id).getFirst().get("after"))
        .contains("MAIL_FAILED");

    // SMTP switched off: no attempt, the link is displayed
    configureSmtp(false);
    String other = "max-" + UUID.randomUUID() + "@stadt.example";
    MvcResult skipped =
        asAdminJson(post(LOCAL_USERS), createBody(other, "Max Muster", "INVITE", "Vertretung"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.emailSent").value(false))
            .andExpect(jsonPath("$.deliveryPath").value("LINK_DISPLAYED"))
            .andReturn();
    UUID otherId =
        UUID.fromString(JsonPath.read(skipped.getResponse().getContentAsString(), "$.user.id"));
    assertThat((String) auditRows("LOCAL_USER_INVITED", otherId).getFirst().get("after"))
        .contains("LINK_DISPLAYED");

    // a resent invitation supersedes the first link: the old token is no longer redeemable
    MvcResult resent =
        asAdmin(post(LOCAL_USERS + "/" + id + "/password-reset"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.deliveryPath").value("LINK_DISPLAYED"))
            .andReturn();
    String secondToken =
        tokenIn(JsonPath.read(resent.getResponse().getContentAsString(), "$.setupUrl"));
    assertThat(secondToken).isNotEqualTo(firstToken);
    assertThat(actionTokens.findRedeemable(firstToken, ActionTokenPurpose.SET_PASSWORD)).isEmpty();
    assertThat(actionTokens.findRedeemable(secondToken, ActionTokenPurpose.SET_PASSWORD))
        .isPresent();
    // an account without a password gets its invitation again, not a reset
    assertThat(auditRows("LOCAL_USER_INVITED", id)).hasSize(2);
    assertThat(auditRows("LOCAL_USER_PASSWORD_RESET_REQUESTED", id)).isEmpty();
  }

  @Test
  void aGeneratedInitialPasswordIsReturnedOnceAndForcesTheChange() throws Exception {
    String email = "erika-" + UUID.randomUUID() + "@stadt.example";
    MvcResult created =
        asAdminJson(
                post(LOCAL_USERS), createBody(email, "Erika Muster", "INITIAL_PASSWORD", "Projekt"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.emailSent").value(false))
            .andExpect(jsonPath("$.deliveryPath").doesNotExist())
            .andExpect(jsonPath("$.setupUrl").doesNotExist())
            .andExpect(jsonPath("$.user.status").value("ACTIVE"))
            .andExpect(jsonPath("$.user.passwordChangeRequired").value(true))
            .andExpect(jsonPath("$.user.passwordChangeReason").value("INITIAL"))
            .andReturn();
    String body = created.getResponse().getContentAsString();
    String password = JsonPath.read(body, "$.initialPassword");
    assertThat(password).hasSizeGreaterThanOrEqualTo(20);
    UUID id = UUID.fromString(JsonPath.read(body, "$.user.id"));
    assertThat(greenMail.getReceivedMessages()).isEmpty();

    assertThat(login(email, password, 200).getResponse().getContentAsString())
        .contains("\"passwordChangeRequired\":true")
        .contains("\"passwordChangeReason\":\"INITIAL\"");
    // a second read carries neither the password nor its hash
    asAdmin(get(LOCAL_USERS + "/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.initialPassword").doesNotExist())
        .andExpect(jsonPath("$.password").doesNotExist());
    assertThat(auditRows("LOCAL_USER_CREATED", id)).hasSize(1);
    assertThat(auditRows("LOCAL_USER_INVITED", id)).isEmpty();
  }

  @Test
  void theAddressIsUniqueAmongLocalAccountsOnlyAndTheReasonIsMandatory() throws Exception {
    String email = "doppelt-" + UUID.randomUUID() + "@stadt.example";
    User oidcTwin = new User("sub-" + UUID.randomUUID(), "https://idp.example", email, "Twin");
    oidcTwin.setOrganizationId(Organization.DEFAULT_ID);
    users.save(oidcTwin);
    try {
      asAdminJson(
              post(LOCAL_USERS), createBody(email, "Erika Muster", "INITIAL_PASSWORD", "Projekt"))
          .andExpect(status().isCreated());
      asAdminJson(
              post(LOCAL_USERS),
              createBody(email.toUpperCase(), "Erika Zwei", "INITIAL_PASSWORD", "Projekt"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
      asAdminJson(
              post(LOCAL_USERS),
              createBody("neu-" + UUID.randomUUID() + "@stadt.example", "N", "INVITE", "  "))
          .andExpect(status().isBadRequest());
      asAdminJson(post(LOCAL_USERS), createBody("keine-adresse", "N", "INVITE", "Grund"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.fieldErrors[0].field").value("email"));
    } finally {
      users.delete(oidcTwin);
    }
  }

  @Test
  void lockingEndsEverySessionAtOnceAndUnlockingResetsTheCounterWithBothMailed() throws Exception {
    LocalAccount user = fixtures.activeUser("erika-" + UUID.randomUUID() + "@stadt.example");
    MvcResult session = login(user.email(), LocalAccountFixtures.PASSWORD, 200);
    login(user.email(), "falsch-1", 401);
    login(user.email(), "falsch-2", 401);
    assertThat(credentials.findById(user.id()).orElseThrow().getFailedLoginAttempts()).isEqualTo(2);

    asAdminJson(
            post(LOCAL_USERS + "/" + user.id() + "/lock"),
            "{\"reason\":\"Dienstende zum Monatsende\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("LOCKED"))
        .andExpect(jsonPath("$.lockedReason").value("ADMIN"));
    LocalCredentials locked = credentials.findById(user.id()).orElseThrow();
    assertThat(locked.getFailedLoginAttempts()).isZero();
    assertThat(locked.getLockoutUntil()).isNull();
    // the running session is over immediately, with the reason in the marker
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(session)))
        .andExpect(status().isUnauthorized())
        .andExpect(
            header()
                .string(
                    "WWW-Authenticate",
                    org.hamcrest.Matchers.containsString("account_locked:admin")));
    mockMvc
        .perform(
            withCsrf(post("/api/v1/auth/local/refresh"), session).cookie(refreshCookie(session)))
        .andExpect(status().isUnauthorized());
    login(user.email(), LocalAccountFixtures.PASSWORD, 401);
    assertThat((String) auditRows("LOCAL_USER_LOCKED", user.id()).getFirst().get("after"))
        .contains("ADMIN")
        .doesNotContain("Dienstende");
    assertThat((String) auditRows("LOCAL_SESSION_REVOKED", user.id()).getFirst().get("after"))
        .contains("ACCOUNT_LOCKED");
    assertThat(greenMail.waitForIncomingEmail(10_000, 1)).isTrue();
    assertThat(plainText(greenMail.getReceivedMessages()[0]))
        .contains("gesperrt")
        .contains("Dienstende zum Monatsende");

    asAdmin(post(LOCAL_USERS + "/" + user.id() + "/lock"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ALREADY_LOCKED"));

    asAdmin(post(LOCAL_USERS + "/" + user.id() + "/unlock"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.lockedReason").doesNotExist());
    assertThat(credentials.findById(user.id()).orElseThrow().getFailedLoginAttempts()).isZero();
    login(user.email(), LocalAccountFixtures.PASSWORD, 200);
    assertThat(auditRows("LOCAL_USER_UNLOCKED", user.id())).hasSize(1);
    assertThat(greenMail.waitForIncomingEmail(10_000, 2)).isTrue();
    assertThat(plainText(greenMail.getReceivedMessages()[1])).contains("freigeschaltet");

    asAdmin(post(LOCAL_USERS + "/" + user.id() + "/unlock"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("NOT_LOCKED"));
  }

  @Test
  void aLockWithoutASessionIsExactlyOneEventAndAFailedLoginLockIsReplaced() throws Exception {
    LocalAccount user = fixtures.activeUser("erika-" + UUID.randomUUID() + "@stadt.example");
    LocalCredentials row = fixtures.credentialsOf(user);
    row.recordLockoutUntil(Instant.now().plus(Duration.ofMinutes(10)), Instant.now());
    fixtures.save(row);

    asAdmin(post(LOCAL_USERS + "/" + user.id() + "/lock"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lockedReason").value("ADMIN"));

    assertThat(auditRows("LOCAL_%", user.id()))
        .extracting(r -> (String) r.get("event_type"))
        .containsExactly("LOCAL_USER_LOCKED");
    assertThat(auditRows("LOCAL_SESSION_REVOKED", user.id())).isEmpty();
  }

  @Test
  void theCallerCanNeitherLockNorDeleteTheOwnAccount() throws Exception {
    asAdmin(post(LOCAL_USERS + "/" + admin.id() + "/lock"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SELF_LOCKOUT"));
    asAdmin(delete(LOCAL_USERS + "/" + admin.id()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SELF_DELETE"));
    // an expiry in the past is a lock by another name - even with a second administrator around
    LocalAccount other = fixtures.activeAdmin("zweite-" + UUID.randomUUID() + "@stadt.example");
    asAdminJson(
            patch(LOCAL_USERS + "/" + admin.id()),
            "{\"expiresAt\":\"" + Instant.now().minusSeconds(60) + "\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SELF_LOCKOUT"));
    assertThat(credentials.findById(admin.id()).orElseThrow().getExpiresAt()).isNull();
    assertThat(credentials.findById(other.id()).orElseThrow().getExpiresAt()).isNull();
    assertThat(credentials.findById(admin.id()).orElseThrow().state(Instant.now()))
        .isEqualTo(LocalAccountState.ACTIVE);
  }

  @Test
  void theLastLoginCapableAdministratorKeepsRoleAndExpiryButNotWhenAnotherRemains()
      throws Exception {
    // the acting administrator is the only login-capable one; expiring the own account is refused
    // as a self-lockout before the guard is even asked (the guard's own refusal on expiry is proved
    // at the service level by the concurrent-lock test and the guard's integration test)
    asAdminJson(
            patch(LOCAL_USERS + "/" + admin.id()),
            "{\"expiresAt\":\"" + Instant.now().minusSeconds(60) + "\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SELF_LOCKOUT"));
    asAdminJson(patch(LOCAL_USERS + "/" + admin.id()), "{\"systemRole\":\"USER\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("LAST_LOGIN_CAPABLE_ADMIN"));
    assertThat(credentials.findById(admin.id()).orElseThrow().getExpiresAt()).isNull();
    assertThat(users.findById(admin.id()).orElseThrow().getSystemRole())
        .isEqualTo(SystemRole.SYSTEM_ADMIN);

    // with a second login-capable administrator the same acts pass
    LocalAccount second = fixtures.activeAdmin("zweite-" + UUID.randomUUID() + "@stadt.example");
    asAdmin(post(LOCAL_USERS + "/" + second.id() + "/lock")).andExpect(status().isOk());
    asAdmin(post(LOCAL_USERS + "/" + second.id() + "/unlock")).andExpect(status().isOk());
    asAdminJson(patch(LOCAL_USERS + "/" + second.id()), "{\"systemRole\":\"USER\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.systemRole").value("USER"));
    assertThat(auditRows("SYSTEM_ADMIN_ROLE_REVOKED", second.id())).hasSize(1);
  }

  /**
   * Two administrators lock each other at the same moment on two real connections: the guard's
   * advisory lock serialises the two counts, so exactly one lock succeeds and the other is refused
   * as the removal of the last login-capable administrator.
   */
  @Test
  void twoConcurrentLocksOfEachOtherLeaveOneAdministrator() throws Exception {
    UUID organizationId = organizations.save(new Organization(UUID.randomUUID(), "Race")).getId();
    User first = localAdmin(organizationId);
    User second = localAdmin(organizationId);
    CurrentUser actorFirst =
        CurrentUser.of(first.getId(), organizationId, SystemRole.SYSTEM_ADMIN, "Eins");
    CurrentUser actorSecond =
        CurrentUser.of(second.getId(), organizationId, SystemRole.SYSTEM_ADMIN, "Zwei");
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Boolean>> outcomes = new ArrayList<>();
      outcomes.add(pool.submit(() -> lockAfter(start, actorFirst, second.getId())));
      outcomes.add(pool.submit(() -> lockAfter(start, actorSecond, first.getId())));
      start.countDown();
      int locked = 0;
      for (Future<Boolean> outcome : outcomes) {
        if (outcome.get(30, TimeUnit.SECONDS)) {
          locked++;
        }
      }
      assertThat(locked).isEqualTo(1);
      long stillCapable =
          List.of(first, second).stream()
              .map(u -> credentials.findById(u.getId()).orElseThrow())
              .filter(row -> row.isLoginCapable(Instant.now()))
              .count();
      assertThat(stillCapable).isEqualTo(1);
    } finally {
      pool.shutdownNow();
      jdbc.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
      for (User user : List.of(first, second)) {
        credentials.deleteById(user.getId());
        users.deleteById(user.getId());
      }
      organizations.deleteById(organizationId);
    }
  }

  private boolean lockAfter(CountDownLatch start, CurrentUser actor, UUID target)
      throws InterruptedException {
    start.await();
    try {
      adminService.lock(actor, target, null);
      return true;
    } catch (ConflictException e) {
      assertThat(e.getCode()).isEqualTo(LocalAdminAvailabilityGuard.ERROR_CODE);
      return false;
    }
  }

  @Test
  void aPasswordResetLinkEndsEverySessionMailsTheLinkAndSupersedesTheOlderOne() throws Exception {
    LocalAccount user = fixtures.activeUser("erika-" + UUID.randomUUID() + "@stadt.example");
    MvcResult session = login(user.email(), LocalAccountFixtures.PASSWORD, 200);

    asAdmin(post(LOCAL_USERS + "/" + user.id() + "/password-reset"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.emailSent").value(true))
        .andExpect(jsonPath("$.deliveryPath").value("MAIL_SENT"))
        .andExpect(jsonPath("$.setupUrl").doesNotExist());
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(session)))
        .andExpect(status().isUnauthorized())
        .andExpect(
            header()
                .string(
                    "WWW-Authenticate",
                    org.hamcrest.Matchers.containsString("session_revoked:admin_reset")));
    assertThat(greenMail.waitForIncomingEmail(10_000, 1)).isTrue();
    MimeMessage mail = greenMail.getReceivedMessages()[0];
    assertThat(mail.getSubject()).contains("zurückgesetzt");
    String firstToken = tokenIn(plainText(mail));
    LocalActionToken token =
        actionTokens.findRedeemable(firstToken, ActionTokenPurpose.RESET_PASSWORD).orElseThrow();
    assertThat(token.getExpiresAt())
        .isAfter(Instant.now().plus(Duration.ofMinutes(28)))
        .isBefore(Instant.now().plus(Duration.ofMinutes(32)));
    assertThat(
            (String)
                auditRows("LOCAL_USER_PASSWORD_RESET_REQUESTED", user.id()).getFirst().get("after"))
        .contains("MAIL_SENT");
    assertThat((String) auditRows("LOCAL_SESSION_REVOKED", user.id()).getFirst().get("after"))
        .contains("ADMIN_RESET");

    asAdmin(post(LOCAL_USERS + "/" + user.id() + "/password-reset")).andExpect(status().isOk());
    assertThat(greenMail.waitForIncomingEmail(10_000, 2)).isTrue();
    assertThat(actionTokens.findRedeemable(firstToken, ActionTokenPurpose.RESET_PASSWORD))
        .isEmpty();
    // the password itself is untouched: the person still signs in with it once unlocked by the link
    assertThat(credentials.findById(user.id()).orElseThrow().getPasswordHash())
        .isEqualTo(user.credentials().getPasswordHash());
  }

  @Test
  void aGeneratedPasswordEndsEverySessionAndForcesTheChangeAtTheNextSignIn() throws Exception {
    LocalAccount user = fixtures.activeUser("erika-" + UUID.randomUUID() + "@stadt.example");
    MvcResult session = login(user.email(), LocalAccountFixtures.PASSWORD, 200);

    MvcResult generated =
        asAdmin(post(LOCAL_USERS + "/" + user.id() + "/password"))
            .andExpect(status().isOk())
            .andReturn();
    String password = JsonPath.read(generated.getResponse().getContentAsString(), "$.password");
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(session)))
        .andExpect(status().isUnauthorized());
    login(user.email(), LocalAccountFixtures.PASSWORD, 401);
    MvcResult next = login(user.email(), password, 200);
    assertThat(next.getResponse().getContentAsString())
        .contains("\"passwordChangeRequired\":true")
        .contains("\"passwordChangeReason\":\"ADMIN_RESET\"");
    assertThat(auditRows("LOCAL_USER_PASSWORD_GENERATED", user.id())).hasSize(1);
    assertThat(String.valueOf(auditRows("LOCAL_USER_PASSWORD_GENERATED", user.id())))
        .doesNotContain(password);
  }

  @Test
  void aChangeAuditsBeforeAndAfterForTheExpiryOnlyAndNamesTheOtherFields() throws Exception {
    LocalAccount user = fixtures.activeUser("erika-" + UUID.randomUUID() + "@stadt.example");
    Instant expiresAt = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
    String newEmail = "neu-" + UUID.randomUUID() + "@stadt.example";

    asAdminJson(
            patch(LOCAL_USERS + "/" + user.id()),
            "{\"displayName\":\"Erika Neu\",\"email\":\""
                + newEmail
                + "\",\"createdReason\":\"Verlängerung Projekt\",\"expiresAt\":\""
                + expiresAt
                + "\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value("Erika Neu"))
        .andExpect(jsonPath("$.email").value(newEmail))
        .andExpect(jsonPath("$.createdReason").value("Verlängerung Projekt"))
        .andExpect(jsonPath("$.expiresAt").value(expiresAt.toString()));
    List<Map<String, Object>> changed = auditRows("LOCAL_USER_CHANGED", user.id());
    assertThat(changed).hasSize(1);
    String before = (String) changed.getFirst().get("before");
    String after = (String) changed.getFirst().get("after");
    assertThat(before).contains("expiresAt");
    assertThat(after)
        .contains(expiresAt.toString())
        .contains("displayName")
        .contains("email")
        .contains("createdReason")
        .doesNotContain("Erika Neu")
        .doesNotContain(newEmail)
        .doesNotContain("Verlängerung");
    // the identity is untouched by the new address
    User reloaded = users.findById(user.id()).orElseThrow();
    assertThat(reloaded.getSubject()).isEqualTo(user.id().toString());
    login(newEmail, LocalAccountFixtures.PASSWORD, 200);

    // removing the expiry is a change of the expiry, nothing else
    asAdminJson(patch(LOCAL_USERS + "/" + user.id()), "{\"noExpiry\":true}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.expiresAt").doesNotExist());
    assertThat(auditRows("LOCAL_USER_CHANGED", user.id())).hasSize(2);
    // a request that changes nothing writes no event
    asAdminJson(patch(LOCAL_USERS + "/" + user.id()), "{\"displayName\":\"Erika Neu\"}")
        .andExpect(status().isOk());
    assertThat(auditRows("LOCAL_USER_CHANGED", user.id())).hasSize(2);
    // an expired account is refused by the validator, not deleted
    MvcResult session = login(newEmail, LocalAccountFixtures.PASSWORD, 200);
    asAdminJson(
            patch(LOCAL_USERS + "/" + user.id()),
            "{\"expiresAt\":\"" + Instant.now().minusSeconds(5) + "\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("EXPIRED"));
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(session)))
        .andExpect(status().isUnauthorized())
        .andExpect(
            header()
                .string(
                    "WWW-Authenticate", org.hamcrest.Matchers.containsString("account_expired")));
  }

  @Test
  void deletionRemovesAnAccountWithoutContentAndRefusesOwnersAndTheBootstrapAccount()
      throws Exception {
    LocalAccount user = fixtures.activeUser("erika-" + UUID.randomUUID() + "@stadt.example");
    // a first request provisions the personal space
    MvcResult session = login(user.email(), LocalAccountFixtures.PASSWORD, 200);
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(session)))
        .andExpect(status().isOk());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM spaces WHERE owner_id = ?", Long.class, user.id()))
        .isEqualTo(1L);
    UUID pseudonym = audit.pseudonymFor(user.id(), Organization.DEFAULT_ID);

    asAdmin(delete(LOCAL_USERS + "/" + user.id())).andExpect(status().isNoContent());

    assertThat(users.findById(user.id())).isEmpty();
    assertThat(credentials.findById(user.id())).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM spaces WHERE owner_id = ?", Long.class, user.id()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM local_refresh_tokens WHERE user_id = ?",
                Long.class,
                user.id()))
        .isZero();
    // the personal space went the audited way of every space deletion
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE event_type = 'SPACE_DELETED'"
                    + " AND object_type = 'SPACE' AND organization_id = ?",
                Long.class,
                Organization.DEFAULT_ID))
        .isEqualTo(1L);
    // the pseudonymised trail stays, the mapping is gone
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE event_type = 'LOCAL_USER_DELETED' AND subject_ref = ?",
                Long.class,
                pseudonym.toString()))
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_actor_pseudonyms WHERE user_id = ?",
                Long.class,
                user.id()))
        .isZero();
    asAdmin(get(LOCAL_USERS + "/" + user.id())).andExpect(status().isNotFound());

    // an owner of a library is locked, not deleted
    LocalAccount owner = fixtures.activeUser("owner-" + UUID.randomUUID() + "@stadt.example");
    KnowledgeLibrary library =
        libraries.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Meine",
                null,
                owner.id(),
                LibraryVisibility.PRIVATE,
                false));
    try {
      asAdmin(delete(LOCAL_USERS + "/" + owner.id()))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("ACCOUNT_OWNS_CONTENT"));
      assertThat(users.findById(owner.id())).isPresent();
    } finally {
      libraries.delete(library);
    }

    // a former group membership is a RESTRICT reference in the evidence tables: the account is
    // locked, not deleted - with the generic answer and the table in the log only
    LocalAccount formerMember =
        fixtures.activeUser("ehemalig-" + UUID.randomUUID() + "@stadt.example");
    Group group =
        groups.save(
            new Group(
                Organization.DEFAULT_ID,
                GroupKind.AD_HOC,
                "Projekt " + UUID.randomUUID(),
                null,
                null,
                null));
    GroupMembershipHistory history =
        groupHistory.save(
            new GroupMembershipHistory(
                group.getId(),
                Organization.DEFAULT_ID,
                formerMember.id(),
                GroupMembershipHistoryCause.ADDED,
                admin.id(),
                Instant.now().minus(Duration.ofDays(1))));
    try {
      MvcResult refused =
          asAdmin(delete(LOCAL_USERS + "/" + formerMember.id()))
              .andExpect(status().isConflict())
              .andExpect(jsonPath("$.code").value("ACCOUNT_OWNS_CONTENT"))
              .andReturn();
      assertThat(refused.getResponse().getContentAsString())
          .contains("Sperren Sie es stattdessen")
          .doesNotContain("group_membership_history");
      assertThat(users.findById(formerMember.id())).isPresent();
    } finally {
      groupHistory.delete(history);
      groups.delete(group);
    }

    // the bootstrap account is never deleted
    LocalAccount bootstrap =
        fixtures.activeAdmin("notanker-" + UUID.randomUUID() + "@stadt.example");
    LocalCredentials row = fixtures.credentialsOf(bootstrap);
    row.markBootstrap();
    fixtures.save(row);
    asAdmin(delete(LOCAL_USERS + "/" + bootstrap.id()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("BOOTSTRAP_ACCOUNT"));
    asAdmin(get(LOCAL_USERS + "/" + bootstrap.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.bootstrap").value(true));
  }

  @Test
  void theListShowsLocalAccountsOnlyWithTheReviewFiltersAndACappedPage() throws Exception {
    String suffix = UUID.randomUUID().toString();
    LocalAccount active = fixtures.activeUser("aktiv-" + suffix + "@stadt.example");
    LocalAccount invited = fixtures.invitedUser("eingeladen-" + suffix + "@stadt.example");
    LocalAccount idle = fixtures.activeUser("ruhend-" + suffix + "@stadt.example");
    LocalAccount limited = fixtures.activeUser("befristet-" + suffix + "@stadt.example");
    LocalCredentials limitedRow = fixtures.credentialsOf(limited);
    limitedRow.setExpiresAt(Instant.now().plus(Duration.ofDays(10)), Instant.now());
    fixtures.save(limitedRow);
    jdbc.update(
        "UPDATE users SET last_login_at = ? WHERE id = ?",
        java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(120))),
        idle.id());
    jdbc.update("UPDATE users SET last_login_at = NULL WHERE id = ?", invited.id());
    User oidcUser =
        new User(
            "sub-" + UUID.randomUUID(),
            "https://idp.example",
            "oidc-" + suffix + "@stadt.example",
            "OIDC");
    oidcUser.setOrganizationId(Organization.DEFAULT_ID);
    users.save(oidcUser);
    try {
      MvcResult all =
          asAdmin(get(LOCAL_USERS).param("query", suffix)).andExpect(status().isOk()).andReturn();
      List<String> emails =
          JsonPath.read(all.getResponse().getContentAsString(), "$.items[*].email");
      assertThat(emails)
          .contains(active.email(), invited.email(), idle.email(), limited.email())
          .doesNotContain(oidcUser.getEmail());
      assertThat(JsonPath.<Integer>read(all.getResponse().getContentAsString(), "$.total"))
          .isEqualTo(4);
      // no timestamp of activity anywhere in the page
      assertThat(all.getResponse().getContentAsString()).doesNotContain("lastLoginAt");

      asAdmin(get(LOCAL_USERS).param("query", suffix).param("status", "INVITED"))
          .andExpect(jsonPath("$.items.length()").value(1))
          .andExpect(jsonPath("$.items[0].email").value(invited.email()))
          .andExpect(jsonPath("$.items[0].activity").value("NEVER"));
      asAdmin(get(LOCAL_USERS).param("query", suffix).param("inactive", "true"))
          .andExpect(
              jsonPath(
                  "$.items[*].email",
                  org.hamcrest.Matchers.containsInAnyOrder(idle.email(), invited.email())));
      asAdmin(get(LOCAL_USERS).param("query", suffix).param("withoutExpiry", "true"))
          .andExpect(
              jsonPath(
                  "$.items[*].email",
                  org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(limited.email()))));
      asAdmin(get(LOCAL_USERS).param("query", suffix).param("sort", "expiresAt").param("size", "1"))
          .andExpect(jsonPath("$.items.length()").value(1))
          .andExpect(jsonPath("$.items[0].email").value(limited.email()))
          .andExpect(jsonPath("$.total").value(4));
      asAdmin(get(LOCAL_USERS).param("size", "51")).andExpect(status().isBadRequest());
      asAdmin(get(LOCAL_USERS).param("sort", "lastLoginAt")).andExpect(status().isBadRequest());

      MvcResult summary =
          asAdmin(get(LOCAL_USERS + "/summary")).andExpect(status().isOk()).andReturn();
      String body = summary.getResponse().getContentAsString();
      assertThat(JsonPath.<Integer>read(body, "$.invitedPending")).isGreaterThanOrEqualTo(1);
      assertThat(JsonPath.<Integer>read(body, "$.withoutExpiry")).isGreaterThanOrEqualTo(4);
      assertThat(JsonPath.<String>read(body, "$.lastReviewHint")).contains("Wiedervorlage");
    } finally {
      users.delete(oidcUser);
    }
  }

  @Test
  void aRegularAccountIsRefusedOnEveryOperation() throws Exception {
    LocalAccount user = fixtures.activeUser("erika-" + UUID.randomUUID() + "@stadt.example");
    String bearer = bearer(login(user.email(), LocalAccountFixtures.PASSWORD, 200));
    mockMvc
        .perform(get(LOCAL_USERS).header(HttpHeaders.AUTHORIZATION, bearer))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post(LOCAL_USERS + "/" + admin.id() + "/lock")
                .header(HttpHeaders.AUTHORIZATION, bearer))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(get("/api/v1/admin/local-auth-settings").header(HttpHeaders.AUTHORIZATION, bearer))
        .andExpect(status().isForbidden());
  }

  /**
   * An older invitation or reset link must not outlive the act that makes it a liability: a lock, a
   * generated password and a changed address each close every open link of the account.
   */
  @Test
  void aLockAGeneratedPasswordAndAChangedAddressCloseEveryOpenLink() throws Exception {
    configureSmtp(false);
    LocalAccount user = fixtures.activeUser("erika-" + UUID.randomUUID() + "@stadt.example");
    String resetToken = resetLink(user.id());
    asAdmin(post(LOCAL_USERS + "/" + user.id() + "/lock")).andExpect(status().isOk());
    assertThat(actionTokens.findRedeemable(resetToken, ActionTokenPurpose.RESET_PASSWORD))
        .isEmpty();
    asAdmin(post(LOCAL_USERS + "/" + user.id() + "/unlock")).andExpect(status().isOk());

    resetToken = resetLink(user.id());
    asAdmin(post(LOCAL_USERS + "/" + user.id() + "/password")).andExpect(status().isOk());
    assertThat(actionTokens.findRedeemable(resetToken, ActionTokenPurpose.RESET_PASSWORD))
        .isEmpty();

    resetToken = resetLink(user.id());
    asAdminJson(
            patch(LOCAL_USERS + "/" + user.id()),
            "{\"email\":\"neu-" + UUID.randomUUID() + "@stadt.example\"}")
        .andExpect(status().isOk());
    assertThat(actionTokens.findRedeemable(resetToken, ActionTokenPurpose.RESET_PASSWORD))
        .isEmpty();

    // an invitation link too
    LocalAccount invited = fixtures.invitedUser("neu-" + UUID.randomUUID() + "@stadt.example");
    String invitationToken = resetLink(invited.id());
    assertThat(actionTokens.findRedeemable(invitationToken, ActionTokenPurpose.SET_PASSWORD))
        .isPresent();
    asAdminJson(
            patch(LOCAL_USERS + "/" + invited.id()),
            "{\"email\":\"anders-" + UUID.randomUUID() + "@stadt.example\"}")
        .andExpect(status().isOk());
    assertThat(actionTokens.findRedeemable(invitationToken, ActionTokenPurpose.SET_PASSWORD))
        .isEmpty();
  }

  /** An expired failed-login lockout leaves a trace but nothing to lift: unlock is refused. */
  @Test
  void anExpiredFailedLoginLockoutIsNotUnlockable() throws Exception {
    LocalAccount user = fixtures.activeUser("erika-" + UUID.randomUUID() + "@stadt.example");
    LocalCredentials row = fixtures.credentialsOf(user);
    row.recordLockoutUntil(Instant.now().minusSeconds(5), Instant.now().minusSeconds(900));
    row.resetFailedLoginAttempts(Instant.now());
    fixtures.save(row);

    asAdmin(post(LOCAL_USERS + "/" + user.id() + "/unlock"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("NOT_LOCKED"));
    assertThat(auditRows("LOCAL_USER_UNLOCKED", user.id())).isEmpty();
  }

  /** The raw token of a link issued through the reset endpoint (SMTP off: link displayed). */
  private String resetLink(UUID userId) throws Exception {
    MvcResult issued =
        asAdmin(post(LOCAL_USERS + "/" + userId + "/password-reset"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.deliveryPath").value("LINK_DISPLAYED"))
            .andReturn();
    return tokenIn(JsonPath.read(issued.getResponse().getContentAsString(), "$.setupUrl"));
  }

  // ---- helpers

  private User localAdmin(UUID organizationId) {
    User user = User.localAccount("race-" + UUID.randomUUID() + "@stadt.example", "Admin");
    user.setOrganizationId(organizationId);
    user.setSystemRole(SystemRole.SYSTEM_ADMIN);
    User saved = users.save(user);
    Instant now = Instant.now();
    LocalCredentials row = new LocalCredentials(saved.getId(), "Testkonto", now);
    row.markEmailVerified(now);
    row.setPasswordHash(user.getId().toString(), now);
    credentials.save(row);
    return saved;
  }

  private org.springframework.test.web.servlet.ResultActions asAdmin(
      MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, adminBearer));
  }

  private org.springframework.test.web.servlet.ResultActions asAdminJson(
      MockHttpServletRequestBuilder request, String json) throws Exception {
    return asAdmin(request.contentType(MediaType.APPLICATION_JSON).content(json));
  }

  private static String createBody(String email, String displayName, String mode, String reason) {
    return "{\"email\":\""
        + email
        + "\",\"displayName\":\""
        + displayName
        + "\",\"mode\":\""
        + mode
        + "\",\"createdReason\":\""
        + reason
        + "\"}";
  }

  private List<Map<String, Object>> auditRows(String eventTypePattern, UUID userId) {
    String pseudonym = audit.pseudonymFor(userId, Organization.DEFAULT_ID).toString();
    return jdbc.queryForList(
        "SELECT event_type, actor_kind, actor_ref, object_label, subject_ref,"
            + " CAST(before AS text) AS before, CAST(after AS text) AS after, reason"
            + " FROM audit_log WHERE event_type LIKE ? AND subject_ref = ? ORDER BY recorded_at",
        eventTypePattern,
        pseudonym);
  }

  private void deleteLocalAuditRows() {
    jdbc.update(
        "DELETE FROM audit_log WHERE event_type LIKE 'LOCAL_USER_%' OR event_type IN"
            + " ('LOCAL_SESSION_REVOKED', 'SYSTEM_ADMIN_ROLE_REVOKED', 'SYSTEM_ADMIN_ROLE_GRANTED')"
            + " OR (event_type IN ('MAIL_SETTINGS_CHANGED', 'SPACE_DELETED')"
            + " AND organization_id = ?)",
        Organization.DEFAULT_ID);
  }

  private void configureSmtp(boolean enabled) {
    mailSettings.updateSettings(
        Organization.DEFAULT_ID,
        admin.id(),
        enabled
            ? new MailSettingsUpdate(
                true,
                "127.0.0.1",
                greenMail.getSmtp().getPort(),
                "kennung",
                "geheim",
                MailEncryption.NONE,
                "opaa@intern.example",
                "OPAA")
            : new MailSettingsUpdate(
                false, null, null, null, "", MailEncryption.STARTTLS, null, null));
  }

  private MvcResult login(String email, String password, int expectedStatus) throws Exception {
    return mockMvc
        .perform(
            post(LOGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
        .andExpect(status().is(expectedStatus))
        .andReturn();
  }

  private static String bearer(MvcResult result) throws Exception {
    return "Bearer " + JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
  }

  private static Cookie refreshCookie(MvcResult result) {
    return result.getResponse().getCookie(LocalRefreshCookies.COOKIE_NAME);
  }

  private static MockHttpServletRequestBuilder withCsrf(
      MockHttpServletRequestBuilder request, MvcResult source) {
    Cookie xsrf = source.getResponse().getCookie("XSRF-TOKEN");
    return request.cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue());
  }

  private static String tokenIn(String text) {
    Matcher matcher = TOKEN_IN_LINK.matcher(text);
    assertThat(matcher.find()).as("link with token in: %s", text).isTrue();
    return matcher.group(1);
  }

  private static String plainText(MimeMessage message) throws Exception {
    Object content = message.getContent();
    if (content instanceof MimeMultipart multipart) {
      for (int i = 0; i < multipart.getCount(); i++) {
        Part part = multipart.getBodyPart(i);
        if (part.isMimeType("text/plain")) {
          return part.getContent().toString();
        }
      }
    }
    return content.toString();
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
