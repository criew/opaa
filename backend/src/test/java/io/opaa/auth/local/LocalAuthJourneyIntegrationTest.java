package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.api.types.LocalAccountState;
import io.opaa.api.types.PasswordChangeReason;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.mail.MailSettingsService;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.LocalMailbox;
import io.opaa.test.OpaaLocalAuthLinkTest;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
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
 * The composed journey of the local account management, as one regression anchor across the
 * sub-issues of Epic #1529 (#1543): the bootstrap administrator signs in, is forced to change its
 * password, invites an account, that account sets its password over the mailed link and signs in,
 * the administrator locks it again, and the refresh token of the ended session is refused as a
 * replay.
 *
 * <p>Every single step has its own test elsewhere - the issuer in {@link
 * LocalAuthFlowIntegrationTest}, the seed in {@link LocalAdminSeederIntegrationTest}, the
 * administration in {@code LocalUserAdminIntegrationTest}, the self-service in {@link
 * LocalSelfServiceIntegrationTest}. What none of them covers is that the steps compose: that the
 * seeded administrator can actually invite, that an invitation redeemed by the person yields an
 * account which signs in, and that a lock reaches the session that very account opened a moment
 * earlier. A regression between two sub-systems shows up here and nowhere else.
 *
 * <p>The seed itself is exercised only through the one outcome this context can produce without a
 * property of its own: {@code opaa.auth.initial-admin-email} carries the shipped default here,
 * which {@link LocalAdminSeeder} refuses (ADR-0033, Entscheidung 5) - the first thing an operator
 * meets after an update. The seeded *state* is then built through the fixtures, because a different
 * address would be a class-local property and split the shared context (AGENTS.md,
 * Spring-Testkontexte).
 */
@OpaaLocalAuthLinkTest
class LocalAuthJourneyIntegrationTest {

  private static final String LOGIN = "/api/v1/auth/local/login";
  private static final String REFRESH = "/api/v1/auth/local/refresh";
  private static final String CHANGE_PASSWORD = "/api/v1/auth/local/change-password";
  private static final String SET_PASSWORD = "/api/v1/auth/local/set-password";
  private static final String LOCAL_USERS = "/api/v1/admin/local-users";
  private static final String ME = "/api/v1/auth/me";
  private static final String DOMAIN = "stadt.example";
  private static final String ADMIN_NEW_PASSWORD = "verwaltung-neues-passwort-2026";
  private static final String INVITED_PASSWORD = "eingeladen-erstes-passwort-2026";

  @Autowired private MockMvc mockMvc;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private LocalAdminSeeder seeder;
  @Autowired private LocalAdminSeedMarkerRepository seedMarker;
  @Autowired private LocalActionTokenRepository actionTokens;
  @Autowired private LocalCredentialsRepository credentials;
  @Autowired private UserRepository users;
  @Autowired private MailSettingsService mailSettings;
  @Autowired private JdbcTemplate jdbc;

  private LocalAccountFixtures fixtures;
  private LocalAccount bootstrapAdmin;

  @BeforeEach
  void setUp() throws Exception {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    actionTokens.deleteAll();
    jdbc.update("DELETE FROM audit_log WHERE event_type LIKE 'LOCAL_%'");
    fixtures.localProvider(true);
    bootstrapAdmin = seededBootstrapAdmin();
  }

  @AfterEach
  void tearDown() {
    fixtures.cleanUp();
  }

  @Test
  void theShippedDefaultAddressIsRefusedByTheSeedAndNothingIsWritten() {
    // The state of a first start: no bootstrap account yet and no marker from an earlier attempt -
    // otherwise the seed skips before it ever looks at the address.
    fixtures.cleanUp();
    seedMarker.deleteAll();
    assertThat(localAccountCount()).isZero();

    // opaa.auth.initial-admin-email is admin@opaa.local here - the shipped default, which would
    // become the sign-in name of a privileged account at a reachable form and is not deliverable.
    LocalAdminSeeder.Outcome outcome = seeder.seedIfNeeded();

    assertThat(outcome).isEqualTo(LocalAdminSeeder.Outcome.REJECTED);
    assertThat(localAccountCount())
        .as("a refused seed writes nothing at all, so a corrected address still works next start")
        .isZero();
    assertThat(seedMarker.seedAlreadyAttempted())
        .as("no marker either - the next start has to try again")
        .isFalse();
  }

  @Test
  void theWholeJourneyFromTheBootstrapAdminToALockedInvitedAccountComposes() throws Exception {
    // The in-JVM SMTP server is started here, not in setUp: stopping it writes mail_settings as the
    // bootstrap administrator, and the other test of this class deletes that account.
    LocalMailbox mailbox = new LocalMailbox(mailSettings, bootstrapAdmin.id());
    mailbox.start();
    try {
      journey(mailbox);
    } finally {
      mailbox.stop();
    }
  }

  private void journey(LocalMailbox mailbox) throws Exception {
    // 1. The bootstrap administrator signs in. Its password change is forced with reason INITIAL,
    //    so the token carries pcr and everything outside /api/v1/auth/local/ is closed.
    MvcResult firstSignIn = login(bootstrapAdmin.email(), LocalAccountFixtures.PASSWORD, 200);
    assertThat(JsonPath.<Boolean>read(body(firstSignIn), "$.passwordChangeRequired")).isTrue();
    assertThat(JsonPath.<String>read(body(firstSignIn), "$.passwordChangeReason"))
        .isEqualTo(PasswordChangeReason.INITIAL.name());
    String pcrBearer = bearer(firstSignIn);
    mockMvc
        .perform(get(LOCAL_USERS).header(HttpHeaders.AUTHORIZATION, pcrBearer))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"))
        .andExpect(jsonPath("$.reason").value(PasswordChangeReason.INITIAL.name()));

    // 2. The change mints a fresh token without pcr right away, so the administrator keeps working
    //    in the same session instead of being sent back to the form.
    MvcResult changed =
        mockMvc
            .perform(
                post(CHANGE_PASSWORD)
                    .header(HttpHeaders.AUTHORIZATION, pcrBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json(
                            Map.of(
                                "currentPassword",
                                LocalAccountFixtures.PASSWORD,
                                "newPassword",
                                ADMIN_NEW_PASSWORD))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.passwordChangeRequired").value(false))
            .andReturn();
    String adminBearer = bearer(changed);
    mockMvc
        .perform(get(LOCAL_USERS).header(HttpHeaders.AUTHORIZATION, adminBearer))
        .andExpect(status().isOk());

    // 3. The administrator invites an account. It is INVITED, has no password, and the link went
    // out
    //    by mail - so the response carries no setupUrl at all.
    String invitedAddress = "eingeladen-" + UUID.randomUUID() + "@" + DOMAIN;
    MvcResult created =
        mockMvc
            .perform(
                post(LOCAL_USERS)
                    .header(HttpHeaders.AUTHORIZATION, adminBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json(
                            Map.of(
                                "email", invitedAddress,
                                "displayName", "Eingeladene Person",
                                "createdReason", "Sachbearbeitung, Vertretung",
                                "mode", "INVITE"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.emailSent").value(true))
            .andExpect(jsonPath("$.deliveryPath").value("MAIL_SENT"))
            .andExpect(jsonPath("$.setupUrl").doesNotExist())
            .andExpect(jsonPath("$.user.status").value(LocalAccountState.INVITED.name()))
            .andReturn();
    UUID invitedId = UUID.fromString(JsonPath.read(body(created), "$.user.id"));

    // An invited account cannot sign in, and the answer is the one every rejection gets.
    login(invitedAddress, INVITED_PASSWORD, 401);

    // 4. The person redeems the token out of the mail and sets the first password.
    assertThat(mailbox.waitFor(1, 10_000)).isTrue();
    MimeMessage invitation = mailbox.messages()[0];
    String token = LocalMailbox.tokenIn(LocalMailbox.plainText(invitation));
    assertThat(token).isNotBlank();
    mockMvc
        .perform(
            post(SET_PASSWORD)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("token", token, "newPassword", INVITED_PASSWORD))))
        .andExpect(status().isNoContent());
    assertThat(credentials.findById(invitedId).orElseThrow().getPasswordHash()).isNotNull();

    // The same token a second time is refused - it is single-use.
    mockMvc
        .perform(
            post(SET_PASSWORD)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("token", token, "newPassword", INVITED_PASSWORD))))
        .andExpect(status().isBadRequest());

    // 5. The account signs in, gets its own session and is provisioned like any other identity.
    MvcResult invitedSignIn = login(invitedAddress, INVITED_PASSWORD, 200);
    String invitedBearer = bearer(invitedSignIn);
    Cookie invitedCookie = refreshCookie(invitedSignIn);
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, invitedBearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(invitedAddress))
        .andExpect(jsonPath("$.createdReason").value("Sachbearbeitung, Vertretung"));

    // 6. The administrator locks the account. That ends its session at once - the token is refused
    //    with the cause, not with a bare "expired" (ADR-0033, Entscheidung 8).
    mockMvc
        .perform(
            post(LOCAL_USERS + "/" + invitedId + "/lock")
                .header(HttpHeaders.AUTHORIZATION, adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value(LocalAccountState.LOCKED.name()));
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, invitedBearer))
        .andExpect(status().isUnauthorized())
        .andExpect(
            result ->
                // The account state is checked before the revocation, so the marker names the lock
                // and its cause rather than the revocation it also triggered - the sharper of the
                // two answers, and the one the SPA turns into "von der Systemverwaltung gesperrt".
                assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                    .contains("account_locked:admin"));

    // 7. And the refresh token of that ended session is refused too - indistinguishably from an
    //    unknown one, with the cookie cleared.
    mockMvc
        .perform(withCsrf(post(REFRESH), invitedSignIn).cookie(invitedCookie))
        .andExpect(status().isUnauthorized());

    // 8. A locked account cannot sign in again either, with the same answer as always.
    login(invitedAddress, INVITED_PASSWORD, 401);

    // The administrator unlocks, and the account works again - the documented way back.
    mockMvc
        .perform(
            post(LOCAL_USERS + "/" + invitedId + "/unlock")
                .header(HttpHeaders.AUTHORIZATION, adminBearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value(LocalAccountState.ACTIVE.name()));
    login(invitedAddress, INVITED_PASSWORD, 200);
  }

  /**
   * The state a first start leaves behind (ADR-0033, Entscheidung 5): a local {@code SYSTEM_ADMIN}
   * marked as the bootstrap account, with its password change forced for reason {@code INITIAL}.
   */
  private LocalAccount seededBootstrapAdmin() {
    LocalAccount account =
        fixtures.userWithForcedPasswordChange(
            "systemverwaltung-" + UUID.randomUUID() + "@" + DOMAIN, PasswordChangeReason.INITIAL);
    User user = account.user();
    user.setSystemRole(SystemRole.SYSTEM_ADMIN);
    fixtures.save(user);
    LocalCredentials row = fixtures.credentialsOf(account);
    row.markBootstrap();
    fixtures.save(row);
    return account;
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

  private long localAccountCount() {
    return users.findAll().stream()
        .filter(user -> LocalIssuer.URN.equals(user.getIssuer()))
        .count();
  }

  private static String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString();
  }

  private static String bearer(MvcResult result) throws Exception {
    return "Bearer " + JsonPath.<String>read(body(result), "$.accessToken");
  }

  private static Cookie refreshCookie(MvcResult result) {
    Cookie cookie = result.getResponse().getCookie(LocalRefreshCookies.COOKIE_NAME);
    assertThat(cookie).as("opaa_refresh cookie").isNotNull();
    return cookie;
  }

  /** Refresh and logout are the only endpoints with CSRF (ADR-0033, Entscheidung 7). */
  private static MockHttpServletRequestBuilder withCsrf(
      MockHttpServletRequestBuilder request, MvcResult source) {
    Cookie xsrf = source.getResponse().getCookie("XSRF-TOKEN");
    assertThat(xsrf).as("XSRF-TOKEN cookie of the sign-in response").isNotNull();
    return request.cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue());
  }

  private static String json(Map<String, Object> values) {
    StringBuilder out = new StringBuilder("{");
    values.forEach(
        (key, value) -> {
          if (out.length() > 1) {
            out.append(',');
          }
          out.append('"').append(key).append("\":");
          if (value instanceof String text) {
            out.append('"').append(text.replace("\"", "\\\"")).append('"');
          } else {
            out.append(value);
          }
        });
    return out.append('}').toString();
  }
}
