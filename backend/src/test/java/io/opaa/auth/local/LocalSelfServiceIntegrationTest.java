package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import io.opaa.api.types.LocalAccountState;
import io.opaa.api.types.LockReason;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.local.LocalAuthSettings.Values;
import io.opaa.mail.MailSettingsService;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.LocalMailbox;
import io.opaa.test.OpaaLocalAuthMockMvcTest;
import jakarta.mail.internet.MimeMessage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/**
 * The self-service of local accounts through the production {@code oidc} chain (ADR-0033,
 * Entscheidungen 3, 9, 11 and 13): the invitation link sets the first password, "forgot password"
 * answers alike for every address in status, body and response-time class and mails only active
 * accounts and accounts in a failed-login lockout, a redeemed reset link lifts that lockout and
 * ends every session, a link is redeemable once and a newer one voids the older, self-registration
 * creates an account that signs in only after the address is confirmed, a taken address and a
 * foreign domain answer exactly like a free one, a still unconfirmed self-registration gets its
 * link again, the password policy answers with field errors, switched-off flows answer with the
 * standard 404, and no token, password or person reaches a log line or an audit row. Mail goes to
 * an in-JVM GreenMail; every link is proved by the message - which is also the point of
 * synchronisation, since the two address-taking flows do their work off the request thread.
 */
@OpaaLocalAuthMockMvcTest
// Own context: the link flows need OPAA_PUBLIC_BASE_URL, which the shared local-auth context
// deliberately leaves unset; the properties are those of LocalUserAdminIntegrationTest, so both
// classes share one context.
@TestPropertySource(
    properties = {
      "opaa.public-base-url=https://opaa.test.example",
      // the SMTP password of the GreenMail account is stored encrypted; the oidc profile ships no
      // key
      "opaa.security.settings.encryption-key=c2V0dGluZ3NkZXZrZXkwMHNldHRpbmdzZGV2a2V5MDE="
    })
class LocalSelfServiceIntegrationTest {

  private static final String LOGIN = "/api/v1/auth/local/login";
  private static final String ME = "/api/v1/auth/me";
  private static final String SET_PASSWORD = "/api/v1/auth/local/set-password";
  private static final String FORGOT_PASSWORD = "/api/v1/auth/local/forgot-password";
  private static final String REGISTER = "/api/v1/auth/local/register";
  private static final String VERIFY_EMAIL = "/api/v1/auth/local/verify-email";
  private static final String LOCAL_USERS = "/api/v1/admin/local-users";
  private static final String NEW_PASSWORD = "neues-sicheres-passwort-2026";
  private static final String DOMAIN = "stadt.example";
  private static final long FLOOR = LocalSelfServiceService.RESPONSE_FLOOR.toMillis();
  private static final long EPSILON = 50;
  private static final long CEILING = 4 * FLOOR;
  private static final int REPETITIONS = 3;

  @Autowired private MockMvc mockMvc;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private LocalCredentialsRepository credentials;
  @Autowired private LocalActionTokenService actionTokens;
  @Autowired private LocalActionTokenRepository actionTokenRepository;
  @Autowired private LocalAuthSettingsRepository settings;
  @Autowired private UserRepository users;
  @Autowired private MailSettingsService mailSettings;
  @Autowired private JdbcTemplate jdbc;

  private LocalAccountFixtures fixtures;
  private LocalAccount admin;
  private String adminBearer;
  private LocalMailbox mailbox;

  @BeforeEach
  void setUp() throws Exception {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    actionTokenRepository.deleteAll();
    deleteLocalAuditRows();
    fixtures.localProvider(true);
    admin = fixtures.activeAdmin("verwaltung-" + UUID.randomUUID() + "@" + DOMAIN);
    adminBearer = bearer(login(admin.email(), LocalAccountFixtures.PASSWORD, 200));
    mailbox = new LocalMailbox(mailSettings, admin.id());
    mailbox.start();
    replaceSettings(Values.defaults());
  }

  @AfterEach
  void tearDown() {
    mailbox.stop();
    replaceSettings(Values.defaults());
    actionTokenRepository.deleteAll();
    deleteLocalAuditRows();
    fixtures.cleanUp();
  }

  // ---- set-password

  @Test
  void anInvitationLinkSetsTheFirstPasswordOnceAndTheAccountSignsIn() throws Exception {
    String email = "erika-" + UUID.randomUUID() + "@" + DOMAIN;
    UUID id = invite(email);
    assertThat(mailbox.waitFor(1, 10_000)).isTrue();
    String text = LocalMailbox.plainText(mailbox.messages()[0]);
    assertThat(text)
        .contains("https://opaa.test.example/" + LocalAccountLinks.SET_PASSWORD_PATH + "?token=");
    String token = LocalMailbox.tokenIn(text);
    // not yet: an invited account has no password
    login(email, NEW_PASSWORD, 401);

    setPassword(token, NEW_PASSWORD).andExpect(status().isNoContent());

    LocalCredentials row = credentials.findById(id).orElseThrow();
    assertThat(row.state(Instant.now())).isEqualTo(LocalAccountState.ACTIVE);
    assertThat(row.getPasswordHash()).isNotNull();
    assertThat(row.isPasswordChangeRequired()).isFalse();
    assertThat(row.getEmailVerifiedAt()).isNotNull();
    assertThat(row.getPasswordInvalidatedBefore()).isNotNull();
    login(email, NEW_PASSWORD, 200);
    // exactly once: the second redemption is the one 400
    setPassword(token, "noch-ein-anderes-passwort-2026")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TOKEN_INVALID"))
        .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    login(email, "noch-ein-anderes-passwort-2026", 401);

    List<Map<String, Object>> set = auditRows("LOCAL_PASSWORD_SET");
    assertThat(set).hasSize(1);
    assertThat(String.valueOf(set.getFirst().values()))
        .contains("SET_PASSWORD")
        .doesNotContain(email)
        .doesNotContain(LocalAccountFixtures.DISPLAY_NAME)
        .doesNotContain(id.toString());
  }

  @Test
  void anExpiredAnUnknownAndAConsumedLinkAreTheSameAnswer() throws Exception {
    LocalAccount user = fixtures.activeUser("erika-" + UUID.randomUUID() + "@" + DOMAIN);
    String expired =
        actionTokens
            .issue(user.id(), ActionTokenPurpose.RESET_PASSWORD, Duration.ofSeconds(-1))
            .rawToken();
    String consumed =
        actionTokens
            .issue(user.id(), ActionTokenPurpose.RESET_PASSWORD, Duration.ofHours(1))
            .rawToken();
    setPassword(consumed, NEW_PASSWORD).andExpect(status().isNoContent());

    String expiredBody = body(setPassword(expired, NEW_PASSWORD).andReturn());
    String unknownBody = body(setPassword("kein-token", NEW_PASSWORD).andReturn());
    String consumedBody = body(setPassword(consumed, NEW_PASSWORD).andReturn());
    // a VERIFY_EMAIL token is no password link either
    String wrongPurpose =
        actionTokens
            .issue(user.id(), ActionTokenPurpose.VERIFY_EMAIL, Duration.ofHours(1))
            .rawToken();
    String wrongPurposeBody = body(setPassword(wrongPurpose, NEW_PASSWORD).andReturn());

    assertThat(expiredBody).contains("\"status\":400").contains("\"code\":\"TOKEN_INVALID\"");
    assertThat(withoutTimestamp(unknownBody)).isEqualTo(withoutTimestamp(expiredBody));
    assertThat(withoutTimestamp(consumedBody)).isEqualTo(withoutTimestamp(expiredBody));
    assertThat(withoutTimestamp(wrongPurposeBody)).isEqualTo(withoutTimestamp(expiredBody));
  }

  @Test
  void aNewerLinkVoidsTheOlderOneAndTheAccountStateIsCheckedOnRedemption() throws Exception {
    LocalAccount user = fixtures.activeUser("erika-" + UUID.randomUUID() + "@" + DOMAIN);
    forgot(user.email()).andExpect(status().isNoContent());
    forgot(user.email()).andExpect(status().isNoContent());
    assertThat(mailbox.waitFor(2, 10_000)).isTrue();
    String first = LocalMailbox.tokenIn(LocalMailbox.plainText(mailbox.messages()[0]));
    String second = LocalMailbox.tokenIn(LocalMailbox.plainText(mailbox.messages()[1]));
    assertThat(first).isNotEqualTo(second);
    setPassword(first, NEW_PASSWORD)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));

    // locked by the administration in the meantime: the open link is refused alike
    LocalCredentials row = fixtures.credentialsOf(user);
    row.lock(LockReason.ADMIN, Instant.now(), null);
    fixtures.save(row);
    setPassword(second, NEW_PASSWORD)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    row = fixtures.credentialsOf(user);
    row.unlock(Instant.now());
    row.setExpiresAt(Instant.now().minusSeconds(1), Instant.now());
    fixtures.save(row);
    setPassword(second, NEW_PASSWORD)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    // the lock and the expiry stay as they are; the password is unchanged
    row = fixtures.credentialsOf(user);
    assertThat(row.state(Instant.now())).isEqualTo(LocalAccountState.EXPIRED);
    row.setExpiresAt(null, Instant.now());
    fixtures.save(row);
    login(user.email(), LocalAccountFixtures.PASSWORD, 200);
    login(user.email(), NEW_PASSWORD, 401);
  }

  @Test
  void aRedeemedResetLinkLiftsAFailedLoginLockoutAndEndsEverySession() throws Exception {
    LocalAccount user = fixtures.activeUser("erika-" + UUID.randomUUID() + "@" + DOMAIN);
    MvcResult session = login(user.email(), LocalAccountFixtures.PASSWORD, 200);
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(session)))
        .andExpect(status().isOk());
    LocalCredentials row = fixtures.credentialsOf(user);
    row.recordLockoutUntil(Instant.now().plus(Duration.ofMinutes(15)), Instant.now());
    fixtures.save(row);
    login(user.email(), LocalAccountFixtures.PASSWORD, 401);

    forgot(user.email()).andExpect(status().isNoContent());
    assertThat(mailbox.waitFor(1, 10_000)).isTrue();
    MimeMessage mail = mailbox.messages()[0];
    assertThat(mail.getAllRecipients()[0].toString()).isEqualTo(user.email());
    assertThat(mail.getSubject()).contains("zurücksetzen");
    String token = LocalMailbox.tokenIn(LocalMailbox.plainText(mail));

    setPassword(token, NEW_PASSWORD).andExpect(status().isNoContent());

    row = fixtures.credentialsOf(user);
    assertThat(row.state(Instant.now())).isEqualTo(LocalAccountState.ACTIVE);
    assertThat(row.getLockedReason()).isNull();
    assertThat(row.getFailedLoginAttempts()).isZero();
    login(user.email(), NEW_PASSWORD, 200);
    login(user.email(), LocalAccountFixtures.PASSWORD, 401);
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(session)))
        .andExpect(status().isUnauthorized());
    assertThat(String.valueOf(auditRows("LOCAL_PASSWORD_SET").getFirst().values()))
        .contains("RESET_PASSWORD");
  }

  @Test
  void theSetPasswordPolicyAnswersWithFieldErrorsAndKeepsTheLinkOpen() throws Exception {
    String email = "erika-" + UUID.randomUUID() + "@" + DOMAIN;
    invite(email);
    assertThat(mailbox.waitFor(1, 10_000)).isTrue();
    String token = LocalMailbox.tokenIn(LocalMailbox.plainText(mailbox.messages()[0]));

    setPassword(token, "kurz")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("newPassword"))
        .andExpect(jsonPath("$.fieldErrors[0].code").value("TOO_SHORT"));
    setPassword(token, "x".repeat(65))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[?(@.code == 'TOO_LONG')]").exists());
    setPassword(token, email)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[?(@.code == 'EQUALS_EMAIL')]").exists());
    setPassword(token, "password12345")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[?(@.code == 'TOO_COMMON')]").exists());
    // the link survived every refusal
    setPassword(token, NEW_PASSWORD).andExpect(status().isNoContent());
    login(email, NEW_PASSWORD, 200);
  }

  // ---- forgot-password

  @Test
  void forgotPasswordAnswersAlikeForEveryAddressAndMailsOnlyTheEligibleAccounts() throws Exception {
    Instant now = Instant.now();
    LocalAccount active = fixtures.activeUser("aktiv-" + UUID.randomUUID() + "@" + DOMAIN);
    LocalAccount lockedOut =
        fixtures.activeUser("fehlversuche-" + UUID.randomUUID() + "@" + DOMAIN);
    LocalCredentials lockedOutRow = fixtures.credentialsOf(lockedOut);
    lockedOutRow.recordLockoutUntil(now.plus(Duration.ofMinutes(15)), now);
    fixtures.save(lockedOutRow);
    LocalAccount adminLocked = fixtures.activeUser("gesperrt-" + UUID.randomUUID() + "@" + DOMAIN);
    LocalCredentials adminLockedRow = fixtures.credentialsOf(adminLocked);
    adminLockedRow.lock(LockReason.ADMIN, now, null);
    fixtures.save(adminLockedRow);
    LocalAccount expired = fixtures.activeUser("abgelaufen-" + UUID.randomUUID() + "@" + DOMAIN);
    LocalCredentials expiredRow = fixtures.credentialsOf(expired);
    expiredRow.setExpiresAt(now.minusSeconds(1), now);
    fixtures.save(expiredRow);
    LocalAccount invited = fixtures.invitedUser("eingeladen-" + UUID.randomUUID() + "@" + DOMAIN);
    String unknown = "niemand-" + UUID.randomUUID() + "@" + DOMAIN;
    MvcResult session = login(active.email(), LocalAccountFixtures.PASSWORD, 200);
    // warm-up: the first calls carry class loading, not the flow's cost
    forgot("aufwaermen-" + UUID.randomUUID() + "@" + DOMAIN).andExpect(status().isNoContent());
    forgot(adminLocked.email()).andExpect(status().isNoContent());

    List<String> cases =
        List.of(
            unknown,
            active.email(),
            lockedOut.email(),
            adminLocked.email(),
            expired.email(),
            invited.email(),
            active.email().toUpperCase());
    Map<String, Long> fastest = new java.util.LinkedHashMap<>();
    for (int repetition = 0; repetition < REPETITIONS; repetition++) {
      for (String email : cases) {
        long start = System.nanoTime();
        MvcResult result = forgot(email).andExpect(status().isNoContent()).andReturn();
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        fastest.merge(email, ms, Math::min);
      }
    }
    assertThat(fastest)
        .allSatisfy((email, ms) -> assertThat(ms).as(email).isBetween(FLOOR - 5, CEILING));
    long spread =
        fastest.values().stream().mapToLong(Long::longValue).max().orElseThrow()
            - fastest.values().stream().mapToLong(Long::longValue).min().orElseThrow();
    assertThat(spread).as("response-time spread %s", fastest).isLessThanOrEqualTo(EPSILON);

    // asking does not end a running session - only the redeemed link does
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(session)))
        .andExpect(status().isOk());
    // mail: the active account (twice per round - the upper-cased address is the same account)
    // and the account in a failed-login lockout; nobody else
    int expectedMails = 3 * REPETITIONS;
    assertThat(mailbox.waitFor(expectedMails, 10_000)).isTrue();
    assertThat(mailbox.waitFor(expectedMails + 1, 1_500)).isFalse();
    List<String> recipients = new ArrayList<>();
    List<String> activeTokens = new ArrayList<>();
    for (MimeMessage message : mailbox.messages()) {
      String recipient = message.getAllRecipients()[0].toString();
      recipients.add(recipient);
      if (recipient.equals(active.email())) {
        activeTokens.add(LocalMailbox.tokenIn(LocalMailbox.plainText(message)));
      }
    }
    assertThat(recipients).hasSize(expectedMails).containsOnly(active.email(), lockedOut.email());
    assertThat(recipients.stream().filter(lockedOut.email()::equals).count())
        .isEqualTo(REPETITIONS);
    // exactly one of the active account's links is still open - each newer one voided the older -
    // and it is a RESET_PASSWORD token with the settings' TTL
    List<LocalActionToken> open =
        activeTokens.stream()
            .flatMap(
                t -> actionTokens.findRedeemable(t, ActionTokenPurpose.RESET_PASSWORD).stream())
            .toList();
    assertThat(open).hasSize(1);
    assertThat(open.getFirst().getUserId()).isEqualTo(active.id());
    assertThat(open.getFirst().getExpiresAt())
        .isAfter(now.plus(Duration.ofMinutes(29)))
        .isBefore(now.plus(Duration.ofMinutes(31)));
    // no state changed, so nothing is audited
    assertThat(auditRows("LOCAL_%")).isEmpty();
  }

  // ---- register and verify-email

  @Test
  void aRegistrationCreatesAnUnconfirmedAccountThatSignsInOnlyAfterVerification() throws Exception {
    replaceSettings(withRegistration(true, List.of(DOMAIN)));
    String email = "neu-" + UUID.randomUUID() + "@" + DOMAIN;
    Instant before = Instant.now();

    register(email, "Neue Person", NEW_PASSWORD)
        .andExpect(status().isAccepted())
        .andExpect(content().string(""));
    // the account and the mail are made off the request thread; the mail is the sync point
    assertThat(mailbox.waitFor(1, 10_000)).isTrue();

    User user = users.findByIssuerAndEmailIgnoreCase(LocalIssuer.URN, email).orElseThrow();
    assertThat(user.getSystemRole()).isEqualTo(SystemRole.USER);
    assertThat(user.getDisplayName()).isEqualTo("Neue Person");
    assertThat(user.getLastLoginAt()).isNull();
    LocalCredentials row = credentials.findById(user.getId()).orElseThrow();
    assertThat(row.getCreatedReason()).isEqualTo("Selbstregistrierung");
    assertThat(row.getPasswordHash()).isNotNull();
    assertThat(row.getEmailVerifiedAt()).isNull();
    assertThat(row.getExpiresAt())
        .isAfter(before.plus(Duration.ofDays(89)))
        .isBefore(before.plus(Duration.ofDays(91)));
    assertThat(row.state(Instant.now())).isEqualTo(LocalAccountState.INVITED);
    // no personal space until the first sign-in
    assertThat(spacesOwnedBy(user.getId())).isZero();
    login(email, NEW_PASSWORD, 401);

    // an administrative reset link sets a password but confirms nothing - the invitation is the
    // administrator's act of vouching, a reset is not
    mockMvc
        .perform(
            post(LOCAL_USERS + "/" + user.getId() + "/password-reset")
                .header(HttpHeaders.AUTHORIZATION, adminBearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deliveryPath").value("MAIL_SENT"));
    assertThat(mailbox.waitFor(2, 10_000)).isTrue();
    String resetToken = LocalMailbox.tokenIn(LocalMailbox.plainText(mailbox.messages()[1]));
    setPassword(resetToken, "zweites-sicheres-passwort-2026").andExpect(status().isNoContent());
    row = credentials.findById(user.getId()).orElseThrow();
    assertThat(row.getEmailVerifiedAt()).isNull();
    assertThat(row.state(Instant.now())).isEqualTo(LocalAccountState.INVITED);
    login(email, "zweites-sicheres-passwort-2026", 401);

    MimeMessage mail = mailbox.messages()[0];
    assertThat(mail.getAllRecipients()[0].toString()).isEqualTo(email);
    String text = LocalMailbox.plainText(mail);
    assertThat(text)
        .contains("https://opaa.test.example/" + LocalAccountLinks.VERIFY_EMAIL_PATH + "?token=");
    String token = LocalMailbox.tokenIn(text);
    LocalActionToken issued =
        actionTokens.findRedeemable(token, ActionTokenPurpose.VERIFY_EMAIL).orElseThrow();
    assertThat(issued.getExpiresAt())
        .isAfter(before.plus(Duration.ofHours(23)))
        .isBefore(before.plus(Duration.ofHours(25)));

    verify(token).andExpect(status().isNoContent());

    row = credentials.findById(user.getId()).orElseThrow();
    assertThat(row.getEmailVerifiedAt()).isNotNull();
    assertThat(row.state(Instant.now())).isEqualTo(LocalAccountState.ACTIVE);
    MvcResult session = login(email, "zweites-sicheres-passwort-2026", 200);
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(session)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.systemRole").value("USER"));
    assertThat(spacesOwnedBy(user.getId())).isEqualTo(1);
    verify(token)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));

    List<Map<String, Object>> registered = auditRows("LOCAL_USER_REGISTERED");
    assertThat(registered).hasSize(1);
    assertThat(registered.getFirst().get("actor_ref")).isEqualTo("local-auth");
    assertThat(String.valueOf(registered.getFirst().values()))
        .doesNotContain(email)
        .doesNotContain("Neue Person")
        .doesNotContain(user.getId().toString());
  }

  @Test
  void aTakenAddressAndAForeignDomainAnswerExactlyLikeAFreeOne() throws Exception {
    replaceSettings(withRegistration(true, List.of(DOMAIN)));
    LocalAccount taken = fixtures.activeUser("belegt-" + UUID.randomUUID() + "@" + DOMAIN);
    String foreign = "fremd-" + UUID.randomUUID() + "@anderswo.example";
    // warm-up
    register("aufwaermen-" + UUID.randomUUID() + "@" + DOMAIN, "Aufwärmen", NEW_PASSWORD)
        .andExpect(status().isAccepted());
    assertThat(mailbox.waitFor(1, 10_000)).isTrue();

    Map<String, Long> fastest = new java.util.LinkedHashMap<>();
    List<String> free = new ArrayList<>();
    for (int repetition = 0; repetition < REPETITIONS; repetition++) {
      String freeAddress = "frei-" + UUID.randomUUID() + "@" + DOMAIN;
      free.add(freeAddress);
      Map<String, String> cases =
          Map.of(
              "taken-upper",
              taken.email().toUpperCase(),
              "foreign",
              foreign,
              "free",
              freeAddress,
              "taken",
              taken.email());
      for (Map.Entry<String, String> testCase : cases.entrySet()) {
        long start = System.nanoTime();
        MvcResult result =
            register(testCase.getValue(), "Irgendwer", NEW_PASSWORD)
                .andExpect(status().isAccepted())
                .andReturn();
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        fastest.merge(testCase.getKey(), ms, Math::min);
      }
    }
    assertThat(fastest)
        .allSatisfy((name, ms) -> assertThat(ms).as(name).isBetween(FLOOR - 5, CEILING));
    long spread =
        fastest.values().stream().mapToLong(Long::longValue).max().orElseThrow()
            - fastest.values().stream().mapToLong(Long::longValue).min().orElseThrow();
    assertThat(spread).as("response-time spread %s", fastest).isLessThanOrEqualTo(EPSILON);

    // the free addresses got their account and their mail; the mail is the sync point for all
    // the work queued before it (one thread, in order)
    assertThat(mailbox.waitFor(1 + REPETITIONS, 10_000)).isTrue();
    assertThat(mailbox.waitFor(2 + REPETITIONS, 1_500)).isFalse();
    List<String> recipients = new ArrayList<>();
    for (MimeMessage message : mailbox.messages()) {
      recipients.add(message.getAllRecipients()[0].toString());
    }
    assertThat(recipients).containsAll(free).doesNotContain(taken.email(), foreign);
    for (String address : free) {
      assertThat(users.findByIssuerAndEmailIgnoreCase(LocalIssuer.URN, address)).isPresent();
    }
    // one account per address, none for the foreign domain, the taken account untouched
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE issuer = ? AND lower(email) = lower(?)",
                Integer.class,
                LocalIssuer.URN,
                taken.email()))
        .isEqualTo(1);
    assertThat(users.findByIssuerAndEmailIgnoreCase(LocalIssuer.URN, foreign)).isEmpty();
    assertThat(fixtures.credentialsOf(taken).getPasswordHash())
        .isEqualTo(taken.credentials().getPasswordHash());
    assertThat(auditRows("LOCAL_USER_REGISTERED")).hasSize(1 + REPETITIONS);
  }

  @Test
  void aStillUnconfirmedSelfRegistrationGetsItsLinkAgainAndNothingElse() throws Exception {
    replaceSettings(withRegistration(true, List.of(DOMAIN)));
    String email = "neu-" + UUID.randomUUID() + "@" + DOMAIN;
    register(email, "Erste Person", NEW_PASSWORD).andExpect(status().isAccepted());
    assertThat(mailbox.waitFor(1, 10_000)).isTrue();
    User user = users.findByIssuerAndEmailIgnoreCase(LocalIssuer.URN, email).orElseThrow();
    String firstHash = credentials.findById(user.getId()).orElseThrow().getPasswordHash();
    String first = LocalMailbox.tokenIn(LocalMailbox.plainText(mailbox.messages()[0]));

    register(email.toUpperCase(), "Zweite Person", "anderes-sicheres-passwort-2026")
        .andExpect(status().isAccepted());

    assertThat(mailbox.waitFor(2, 10_000)).isTrue();
    String second = LocalMailbox.tokenIn(LocalMailbox.plainText(mailbox.messages()[1]));
    assertThat(second).isNotEqualTo(first);
    // one account, name and hash of the first registration, the first link voided
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE issuer = ? AND lower(email) = lower(?)",
                Integer.class,
                LocalIssuer.URN,
                email))
        .isEqualTo(1);
    assertThat(users.findById(user.getId()).orElseThrow().getDisplayName())
        .isEqualTo("Erste Person");
    assertThat(credentials.findById(user.getId()).orElseThrow().getPasswordHash())
        .isEqualTo(firstHash);
    assertThat(actionTokens.findRedeemable(first, ActionTokenPurpose.VERIFY_EMAIL)).isEmpty();
    verify(second).andExpect(status().isNoContent());
    login(email, NEW_PASSWORD, 200);
    login(email, "anderes-sicheres-passwort-2026", 401);
    // the second registration created nothing and is no event
    assertThat(auditRows("LOCAL_USER_REGISTERED")).hasSize(1);
  }

  @Test
  void theRegistrationRefusesAnInvalidAddressNameOrPasswordWithFieldErrors() throws Exception {
    replaceSettings(withRegistration(true, List.of(DOMAIN)));
    register("keine-adresse", "Name", NEW_PASSWORD)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("email"))
        .andExpect(jsonPath("$.fieldErrors[0].code").value("INVALID_ADDRESS"));
    register("x-" + UUID.randomUUID() + "@" + DOMAIN, "   ", NEW_PASSWORD)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("displayName"))
        .andExpect(jsonPath("$.fieldErrors[0].code").value("REQUIRED"));
    String email = "x-" + UUID.randomUUID() + "@" + DOMAIN;
    register(email, "Name", "kurz")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("password"))
        .andExpect(jsonPath("$.fieldErrors[0].code").value("TOO_SHORT"));
    register(email, "Name", email)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[?(@.code == 'EQUALS_EMAIL')]").exists());
    register(email, "Name", "password12345")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[?(@.code == 'TOO_COMMON')]").exists());
    assertThat(mailbox.waitFor(1, 1_000)).isFalse();
    assertThat(users.findByIssuerAndEmailIgnoreCase(LocalIssuer.URN, email)).isEmpty();
  }

  // ---- switched-off flows

  @Test
  void aSwitchedOffFlowAnswersWithTheStandard404() throws Exception {
    // the honest baseline: an unknown route under /api answers 401 without a session, so the 404
    // only hides the endpoint from a caller who does not look closely (#1592)
    mockMvc
        .perform(
            post("/api/v1/auth/local/unbekannt-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
    String standard404 = LocalSelfServiceUnavailableIntegrationTest.STANDARD_404;
    String email = "wer-" + UUID.randomUUID() + "@" + DOMAIN;

    // registration: off by default; on without a domain list; management off
    assertThat(withoutTimestamp(body(register(email, "Name", NEW_PASSWORD).andReturn())))
        .isEqualTo(standard404);
    replaceSettings(withRegistration(true, List.of()));
    assertThat(withoutTimestamp(body(register(email, "Name", NEW_PASSWORD).andReturn())))
        .isEqualTo(standard404);
    replaceSettings(withRegistration(true, List.of(DOMAIN)));
    register(email, "Name", NEW_PASSWORD).andExpect(status().isAccepted());
    assertThat(mailbox.waitFor(1, 10_000)).isTrue();
    fixtures.localProvider(false);
    assertThat(withoutTimestamp(body(register(email, "Name", NEW_PASSWORD).andReturn())))
        .isEqualTo(standard404);
    assertThat(withoutTimestamp(body(forgot(email).andReturn()))).isEqualTo(standard404);
    fixtures.localProvider(true);

    // forgot password: on by default, off by the setting
    forgot(email).andExpect(status().isNoContent());
    replaceSettings(withPasswordReset(false));
    assertThat(withoutTimestamp(body(forgot(email).andReturn()))).isEqualTo(standard404);
    // the link endpoints stay reachable whatever the switches say
    setPassword("kein-token", NEW_PASSWORD)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    verify("kein-token")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
  }

  // ---- log and audit privacy

  @Test
  void theSelfServiceLeaksNoTokenPasswordOrPersonIntoLogsOrAuditRows() throws Exception {
    replaceSettings(withRegistration(true, List.of(DOMAIN)));
    Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    // the SMTP transport's protocol trace prints every mail on the wire - the link included - and
    // is pinned to INFO in application.yml; the test relies on the pin, so it checks it is there
    // (S3LogLeakTest pattern) and does not touch that logger. GreenMail's line and mailbox log is
    // the receiving end of the same wire (test server, not OPAA) and is muted here.
    try (InputStream yml = getClass().getResourceAsStream("/application.yml")) {
      assertThat(yml).isNotNull();
      assertThat(new String(yml.readAllBytes(), StandardCharsets.UTF_8))
          .contains("    org.eclipse.angus.mail: INFO\n");
    }
    Logger greenMailWire = (Logger) LoggerFactory.getLogger("com.icegreen.greenmail");
    Level previous = root.getLevel();
    Level previousGreenMail = greenMailWire.getLevel();
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    root.setLevel(Level.TRACE);
    greenMailWire.setLevel(Level.WARN);
    logs.start();
    root.addAppender(logs);
    try {
      String email = "neu-" + UUID.randomUUID() + "@" + DOMAIN;
      register(email, "Neue Person", NEW_PASSWORD).andExpect(status().isAccepted());
      assertThat(mailbox.waitFor(1, 10_000)).isTrue();
      String verification = LocalMailbox.tokenIn(LocalMailbox.plainText(mailbox.messages()[0]));
      verify(verification).andExpect(status().isNoContent());
      forgot(email).andExpect(status().isNoContent());
      assertThat(mailbox.waitFor(2, 10_000)).isTrue();
      String reset = LocalMailbox.tokenIn(LocalMailbox.plainText(mailbox.messages()[1]));
      setPassword(reset, "zweites-sicheres-passwort-2026").andExpect(status().isNoContent());
      setPassword(reset, "zweites-sicheres-passwort-2026").andExpect(status().isBadRequest());

      List<String> secrets =
          List.of(verification, reset, NEW_PASSWORD, "zweites-sicheres-passwort-2026");
      List<String> personal = List.of(email, email.toUpperCase(), "Neue Person");
      List<ILoggingEvent> captured = new ArrayList<>(logs.list);
      assertThat(captured).anyMatch(event -> event.getLoggerName().startsWith("io.opaa"));
      for (ILoggingEvent event : captured) {
        String line = event.getFormattedMessage();
        String where = "log line of " + event.getLoggerName() + " at " + event.getLevel();
        for (String secret : secrets) {
          assertThat(line).as(where).doesNotContain(secret);
        }
        boolean ours = event.getLoggerName().startsWith("io.opaa");
        if (ours || event.getLevel().isGreaterOrEqual(Level.INFO)) {
          for (String value : personal) {
            assertThat(line).as(where).doesNotContain(value);
          }
        }
      }
      List<Map<String, Object>> events = auditRows("LOCAL_%");
      assertThat(events)
          .extracting(row -> (String) row.get("event_type"))
          .containsExactlyInAnyOrder("LOCAL_USER_REGISTERED", "LOCAL_PASSWORD_SET");
      UUID id = users.findByIssuerAndEmailIgnoreCase(LocalIssuer.URN, email).orElseThrow().getId();
      for (Map<String, Object> row : events) {
        String text = String.valueOf(row.values());
        assertThat(text)
            .as("audit row %s", row.get("event_type"))
            .doesNotContain(email)
            .doesNotContain("Neue Person")
            .doesNotContain(id.toString());
        for (String secret : secrets) {
          assertThat(text).doesNotContain(secret);
        }
      }
    } finally {
      root.detachAppender(logs);
      root.setLevel(previous);
      greenMailWire.setLevel(previousGreenMail);
    }
  }

  // ---- helpers

  private UUID invite(String email) throws Exception {
    MvcResult created =
        mockMvc
            .perform(
                post(LOCAL_USERS)
                    .header(HttpHeaders.AUTHORIZATION, adminBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"email\":\""
                            + email
                            + "\",\"displayName\":\""
                            + LocalAccountFixtures.DISPLAY_NAME
                            + "\",\"mode\":\"INVITE\",\"createdReason\":\"Testkonto\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.deliveryPath").value("MAIL_SENT"))
            .andReturn();
    return UUID.fromString(JsonPath.read(body(created), "$.user.id"));
  }

  private org.springframework.test.web.servlet.ResultActions setPassword(
      String token, String newPassword) throws Exception {
    return mockMvc.perform(
        post(SET_PASSWORD)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("token", token, "newPassword", newPassword))));
  }

  private org.springframework.test.web.servlet.ResultActions forgot(String email) throws Exception {
    return mockMvc.perform(
        post(FORGOT_PASSWORD)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("email", email))));
  }

  private org.springframework.test.web.servlet.ResultActions register(
      String email, String displayName, String password) throws Exception {
    return mockMvc.perform(
        post(REGISTER)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                json(Map.of("email", email, "displayName", displayName, "password", password))));
  }

  private org.springframework.test.web.servlet.ResultActions verify(String token) throws Exception {
    return mockMvc.perform(
        post(VERIFY_EMAIL)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("token", token))));
  }

  private MvcResult login(String email, String password, int expectedStatus) throws Exception {
    return mockMvc
        .perform(
            post(LOGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", email, "password", password))))
        .andExpect(status().is(expectedStatus))
        .andReturn();
  }

  private void replaceSettings(Values values) {
    LocalAuthSettings row = settings.findSingleton().orElseThrow();
    row.replace(values, null, Instant.now());
    settings.save(row);
  }

  private static Values withRegistration(boolean enabled, List<String> domains) {
    Values d = Values.defaults();
    return new Values(
        enabled,
        domains,
        d.passwordResetEnabled(),
        d.passwordMinLength(),
        d.invitationTokenTtlHours(),
        d.resetTokenTtlMinutes(),
        d.defaultExpiryDays(),
        d.inactiveDays());
  }

  private static Values withPasswordReset(boolean enabled) {
    Values d = Values.defaults();
    return new Values(
        d.selfRegistrationEnabled(),
        d.selfRegistrationAllowedDomains(),
        enabled,
        d.passwordMinLength(),
        d.invitationTokenTtlHours(),
        d.resetTokenTtlMinutes(),
        d.defaultExpiryDays(),
        d.inactiveDays());
  }

  private int spacesOwnedBy(UUID userId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM spaces WHERE owner_id = ?", Integer.class, userId);
  }

  private List<Map<String, Object>> auditRows(String typePattern) {
    return jdbc.queryForList(
        "SELECT event_type, actor_ref, object_label, subject_ref, CAST(before AS text) AS before,"
            + " CAST(after AS text) AS after, reason FROM audit_log WHERE event_type LIKE ?",
        typePattern);
  }

  private void deleteLocalAuditRows() {
    jdbc.update("DELETE FROM audit_log WHERE event_type LIKE 'LOCAL_%'");
  }

  private static String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString();
  }

  private static String withoutTimestamp(String json) {
    return json.replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"…\"");
  }

  private static String bearer(MvcResult result) throws Exception {
    return "Bearer " + JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
  }

  private static String json(Map<String, String> fields) {
    StringBuilder sb = new StringBuilder("{");
    fields.forEach(
        (key, value) -> {
          if (sb.length() > 1) {
            sb.append(',');
          }
          sb.append('"').append(key).append("\":\"").append(value).append('"');
        });
    return sb.append('}').toString();
  }
}
