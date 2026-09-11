package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.opaa.api.types.PasswordChangeReason;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.UserRepository;
import io.opaa.security.LocalAuthKeyService;
import io.opaa.security.LocalAuthKeyService.Purpose;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthMockMvcTest;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The local token issuer end to end (ADR-0033, Entscheidungen 6-9 and 13) through the production
 * {@code oidc} filter chain against Postgres: sign-in with cookie and CSRF token, the rotation and
 * the replay of the refresh cookie, the immediate revocation on sign-out and password change, the
 * forced password change, the management switch, the reasons every refused token names in {@code
 * WWW-Authenticate}, the finder-never-provisioner rule of the local issuer and the public
 * configuration. A revocation cuts off the whole current second ({@code iat} is second-granular),
 * so no test has to wait for a second boundary.
 */
@OpaaLocalAuthMockMvcTest
class LocalAuthFlowIntegrationTest {

  private static final String LOGIN = "/api/v1/auth/local/login";
  private static final String REFRESH = "/api/v1/auth/local/refresh";
  private static final String LOGOUT = "/api/v1/auth/local/logout";
  private static final String CHANGE_PASSWORD = "/api/v1/auth/local/change-password";
  private static final String ME = "/api/v1/auth/me";
  private static final String NEW_PASSWORD = "neues-sicheres-passwort-2026";

  @Autowired private MockMvc mockMvc;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private LocalAuthKeyService keys;
  @Autowired private UserRepository users;
  @Autowired private JdbcTemplate jdbc;

  private LocalAccountFixtures fixtures;
  private LocalAccount user;

  @BeforeEach
  void setUp() {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    fixtures.localProvider(true);
    user = fixtures.activeUser("erika-" + UUID.randomUUID() + "@stadt.example");
  }

  @AfterEach
  void tearDown() {
    fixtures.cleanUp();
  }

  @Test
  void aSignInIssuesATokenThatAuthenticatesAndTheRefreshCookieWithTheAdrsAttributes()
      throws Exception {
    MvcResult login =
        login(user.email(), LocalAccountFixtures.PASSWORD)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isString())
            .andExpect(jsonPath("$.expiresInSeconds").value(900))
            .andExpect(jsonPath("$.passwordChangeRequired").value(false))
            .andExpect(jsonPath("$.passwordChangeReason").doesNotExist())
            .andExpect(cookie().exists(LocalRefreshCookies.COOKIE_NAME))
            .andExpect(cookie().httpOnly(LocalRefreshCookies.COOKIE_NAME, true))
            .andExpect(cookie().secure(LocalRefreshCookies.COOKIE_NAME, true))
            .andExpect(cookie().path(LocalRefreshCookies.COOKIE_NAME, "/api/v1/auth/local"))
            .andExpect(cookie().attribute(LocalRefreshCookies.COOKIE_NAME, "SameSite", "Strict"))
            .andExpect(cookie().maxAge(LocalRefreshCookies.COOKIE_NAME, 7 * 24 * 3600))
            .andExpect(cookie().exists("XSRF-TOKEN"))
            .andReturn();
    // the refresh token never appears in the body
    assertThat(login.getResponse().getContentAsString()).doesNotContain("refresh");

    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(login)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(user.id().toString()))
        .andExpect(jsonPath("$.email").value(user.email()))
        .andExpect(jsonPath("$.displayName").value(LocalAccountFixtures.DISPLAY_NAME))
        .andExpect(jsonPath("$.systemRole").value("USER"));
  }

  @Test
  void everyRefusedSignInIsTheSameAnswer() throws Exception {
    LocalAccount locked = fixtures.activeUser("locked-" + UUID.randomUUID() + "@stadt.example");
    LocalCredentials lockedRow = fixtures.credentialsOf(locked);
    lockedRow.lock(LockReason.ADMIN, Instant.now(), null);
    fixtures.save(lockedRow);
    LocalAccount invited = fixtures.invitedUser("invited-" + UUID.randomUUID() + "@stadt.example");

    String unknownAddress = body(login("niemand@stadt.example", "irgendwas"));
    String wrongPassword = body(login(user.email(), "falsches-passwort"));
    String lockedAccount = body(login(locked.email(), LocalAccountFixtures.PASSWORD));
    String invitedAccount = body(login(invited.email(), LocalAccountFixtures.PASSWORD));

    assertThat(wrongPassword).isEqualTo(unknownAddress);
    assertThat(lockedAccount).isEqualTo(unknownAddress);
    assertThat(invitedAccount).isEqualTo(unknownAddress);
    assertThat(unknownAddress).contains("\"status\":401").doesNotContain("locked", "gesperrt");
    // the envelope's optional fields are absent, not empty, when they do not apply
    login("niemand@stadt.example", "irgendwas")
        .andExpect(jsonPath("$.fieldErrors").doesNotExist())
        .andExpect(jsonPath("$.code").doesNotExist())
        .andExpect(jsonPath("$.reason").doesNotExist());
  }

  @Test
  void aRefreshRotatesTheCookieAndAReplayEndsEverySessionOfTheAccountWithAReason()
      throws Exception {
    ListAppender<ILoggingEvent> warnings = attachTo(LocalRefreshTokenService.class);
    long auditedBefore = auditCount("LOCAL_SESSION_REVOKED");
    MvcResult login = login(user.email(), LocalAccountFixtures.PASSWORD).andReturn();
    Cookie first = refreshCookie(login);

    MvcResult refreshed =
        mockMvc
            .perform(withCsrf(post(REFRESH), login).cookie(first))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isString())
            .andExpect(cookie().exists(LocalRefreshCookies.COOKIE_NAME))
            .andReturn();
    Cookie second = refreshCookie(refreshed);
    assertThat(second.getValue()).isNotEqualTo(first.getValue());
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(refreshed)))
        .andExpect(status().isOk());

    MvcResult otherSession = login(user.email(), LocalAccountFixtures.PASSWORD).andReturn();

    // the replay: the already rotated cookie is presented again
    mockMvc
        .perform(withCsrf(post(REFRESH), login).cookie(first))
        .andExpect(status().isUnauthorized())
        .andExpect(cookie().maxAge(LocalRefreshCookies.COOKIE_NAME, 0))
        .andExpect(jsonPath("$.status").value(401));
    // the successor is dead with its family ...
    mockMvc
        .perform(withCsrf(post(REFRESH), login).cookie(second))
        .andExpect(status().isUnauthorized())
        .andExpect(cookie().maxAge(LocalRefreshCookies.COOKIE_NAME, 0));
    // ... and so is the access token minted with it, naming the reason
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(refreshed)))
        .andExpect(status().isUnauthorized())
        .andExpect(
            header()
                .string(
                    HttpHeaders.WWW_AUTHENTICATE,
                    containsString("error_description=\"session_revoked:reuse_detected\"")));
    // every other session of the account is over as well - cookie and access token alike
    mockMvc
        .perform(withCsrf(post(REFRESH), otherSession).cookie(refreshCookie(otherSession)))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(otherSession)))
        .andExpect(status().isUnauthorized())
        .andExpect(
            header()
                .string(
                    HttpHeaders.WWW_AUTHENTICATE,
                    containsString("error_description=\"session_revoked:reuse_detected\"")));
    assertThat(auditCount("LOCAL_SESSION_REVOKED")).isEqualTo(auditedBefore + 1);
    assertThat(warnings.list)
        .filteredOn(event -> event.getLevel() == Level.WARN)
        .extracting(ILoggingEvent::getFormattedMessage)
        .anySatisfy(
            message ->
                assertThat(message)
                    .contains(user.id().toString())
                    .doesNotContain(user.email())
                    .doesNotContain(first.getValue()));
  }

  @Test
  void anUnknownCookieAndAMissingCookieAreRefusedAlike() throws Exception {
    MvcResult login = login(user.email(), LocalAccountFixtures.PASSWORD).andReturn();

    String unknown =
        body(
            mockMvc
                .perform(
                    withCsrf(post(REFRESH), login)
                        .cookie(new Cookie(LocalRefreshCookies.COOKIE_NAME, "kein-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().maxAge(LocalRefreshCookies.COOKIE_NAME, 0)));
    String missing =
        body(mockMvc.perform(withCsrf(post(REFRESH), login)).andExpect(status().isUnauthorized()));

    assertThat(unknown).isEqualTo(missing);
  }

  @Test
  void aSignOutRevokesTheAccessTokenImmediatelyAndTheFamilyWithoutAnAuditEvent() throws Exception {
    long auditedBefore = auditCount("LOCAL_SESSION_REVOKED");
    MvcResult login = login(user.email(), LocalAccountFixtures.PASSWORD).andReturn();
    Cookie refresh = refreshCookie(login);

    mockMvc
        .perform(
            withCsrf(post(LOGOUT), login)
                .cookie(refresh)
                .header(HttpHeaders.AUTHORIZATION, bearer(login)))
        .andExpect(status().isNoContent())
        .andExpect(cookie().maxAge(LocalRefreshCookies.COOKIE_NAME, 0));

    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(login)))
        .andExpect(status().isUnauthorized())
        .andExpect(
            header()
                .string(
                    HttpHeaders.WWW_AUTHENTICATE,
                    containsString("error_description=\"session_revoked\"")));
    mockMvc
        .perform(withCsrf(post(REFRESH), login).cookie(refresh))
        .andExpect(status().isUnauthorized());
    // a second sign-out without any cookie is fine too
    mockMvc.perform(withCsrf(post(LOGOUT), login)).andExpect(status().isNoContent());
    assertThat(auditCount("LOCAL_SESSION_REVOKED")).isEqualTo(auditedBefore);
  }

  @Test
  void aPasswordChangeEndsEverySessionAndContinuesThisOneWithAFreshTokenAndCookie()
      throws Exception {
    long auditedBefore = auditCount("LOCAL_PASSWORD_CHANGED");
    MvcResult thisSession = login(user.email(), LocalAccountFixtures.PASSWORD).andReturn();
    MvcResult otherSession = login(user.email(), LocalAccountFixtures.PASSWORD).andReturn();

    MvcResult changed =
        mockMvc
            .perform(
                post(CHANGE_PASSWORD)
                    .header(HttpHeaders.AUTHORIZATION, bearer(thisSession))
                    .cookie(refreshCookie(thisSession))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json(
                            Map.of(
                                "currentPassword",
                                LocalAccountFixtures.PASSWORD,
                                "newPassword",
                                NEW_PASSWORD))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isString())
            .andExpect(jsonPath("$.passwordChangeRequired").value(false))
            .andExpect(cookie().exists(LocalRefreshCookies.COOKIE_NAME))
            .andReturn();
    assertThat(refreshCookie(changed).getValue())
        .isNotEqualTo(refreshCookie(thisSession).getValue());

    // the fresh token works (minted at the cutoff, in the same second as the change) ...
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(changed)))
        .andExpect(status().isOk());
    // ... both older ones name the reason ...
    for (MvcResult old : new MvcResult[] {thisSession, otherSession}) {
      mockMvc
          .perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(old)))
          .andExpect(status().isUnauthorized())
          .andExpect(
              header()
                  .string(
                      HttpHeaders.WWW_AUTHENTICATE,
                      containsString("error_description=\"session_revoked:password_changed\"")));
    }
    // ... every old family is revoked, the fresh one continues this session
    for (MvcResult old : new MvcResult[] {thisSession, otherSession}) {
      mockMvc
          .perform(withCsrf(post(REFRESH), old).cookie(refreshCookie(old)))
          .andExpect(status().isUnauthorized());
    }
    mockMvc
        .perform(withCsrf(post(REFRESH), changed).cookie(refreshCookie(changed)))
        .andExpect(status().isOk());
    // and only the new password signs in
    login(user.email(), LocalAccountFixtures.PASSWORD).andExpect(status().isUnauthorized());
    login(user.email(), NEW_PASSWORD).andExpect(status().isOk());
    assertThat(auditCount("LOCAL_PASSWORD_CHANGED")).isEqualTo(auditedBefore + 1);
    assertThat(fixtures.credentialsOf(user).getPasswordInvalidatedBefore()).isNotNull();
  }

  /**
   * The single-session first change: the one case where no other family exists to leave a trace.
   */
  @Test
  void theFirstChangeInASingleSessionStillNamesPasswordChangedAsTheCause() throws Exception {
    MvcResult only = login(user.email(), LocalAccountFixtures.PASSWORD).andReturn();

    mockMvc
        .perform(
            post(CHANGE_PASSWORD)
                .header(HttpHeaders.AUTHORIZATION, bearer(only))
                .cookie(refreshCookie(only))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json(
                        Map.of(
                            "currentPassword",
                            LocalAccountFixtures.PASSWORD,
                            "newPassword",
                            NEW_PASSWORD))))
        .andExpect(status().isOk());

    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(only)))
        .andExpect(status().isUnauthorized())
        .andExpect(
            header()
                .string(
                    HttpHeaders.WWW_AUTHENTICATE,
                    containsString("error_description=\"session_revoked:password_changed\"")));
  }

  @Test
  void aPasswordChangeRejectsTheWrongCurrentPasswordAndAWeakNewOneWithFieldErrors()
      throws Exception {
    MvcResult login = login(user.email(), LocalAccountFixtures.PASSWORD).andReturn();

    mockMvc
        .perform(
            post(CHANGE_PASSWORD)
                .header(HttpHeaders.AUTHORIZATION, bearer(login))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("currentPassword", "falsch", "newPassword", NEW_PASSWORD))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("currentPassword"))
        .andExpect(jsonPath("$.fieldErrors[0].code").value("WRONG_PASSWORD"));
    mockMvc
        .perform(
            post(CHANGE_PASSWORD)
                .header(HttpHeaders.AUTHORIZATION, bearer(login))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json(
                        Map.of(
                            "currentPassword",
                            LocalAccountFixtures.PASSWORD,
                            "newPassword",
                            "sonnenschein"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("newPassword"))
        .andExpect(jsonPath("$.fieldErrors[0].code").value("TOO_COMMON"));
    // nothing changed: the old password still signs in and the token still works
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(login)))
        .andExpect(status().isOk());
    login(user.email(), LocalAccountFixtures.PASSWORD).andExpect(status().isOk());
  }

  @Test
  void aForcedPasswordChangeBlocksEverythingButTheLocalAuthEndpointsAndNamesItsReason()
      throws Exception {
    LocalAccount forced =
        fixtures.userWithForcedPasswordChange(
            "neu-" + UUID.randomUUID() + "@stadt.example", PasswordChangeReason.ADMIN_RESET);
    MvcResult login =
        login(forced.email(), LocalAccountFixtures.PASSWORD)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.passwordChangeRequired").value(true))
            .andExpect(jsonPath("$.passwordChangeReason").value("ADMIN_RESET"))
            .andReturn();

    for (String path : new String[] {ME, "/api/v1/spaces", "/api/v1/me/groups"}) {
      mockMvc
          .perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(login)))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.status").value(403))
          .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"))
          .andExpect(jsonPath("$.reason").value("ADMIN_RESET"));
    }
    // refresh stays possible - the forced change survives it
    MvcResult refreshed =
        mockMvc
            .perform(withCsrf(post(REFRESH), login).cookie(refreshCookie(login)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.passwordChangeRequired").value(true))
            .andReturn();
    MvcResult changed =
        mockMvc
            .perform(
                post(CHANGE_PASSWORD)
                    .header(HttpHeaders.AUTHORIZATION, bearer(refreshed))
                    .cookie(refreshCookie(refreshed))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json(
                            Map.of(
                                "currentPassword",
                                LocalAccountFixtures.PASSWORD,
                                "newPassword",
                                NEW_PASSWORD))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.passwordChangeRequired").value(false))
            .andExpect(jsonPath("$.passwordChangeReason").doesNotExist())
            .andExpect(cookie().exists(LocalRefreshCookies.COOKIE_NAME))
            .andReturn();
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(changed)))
        .andExpect(status().isOk());
    mockMvc
        .perform(withCsrf(post(REFRESH), changed).cookie(refreshCookie(changed)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.passwordChangeRequired").value(false));
    assertThat(fixtures.credentialsOf(forced).isPasswordChangeRequired()).isFalse();
  }

  @Test
  void switchingTheManagementOffRefusesRegularAccountsButNotSystemAdmins() throws Exception {
    LocalAccount admin = fixtures.activeAdmin("admin-" + UUID.randomUUID() + "@stadt.example");
    MvcResult userSession = login(user.email(), LocalAccountFixtures.PASSWORD).andReturn();
    MvcResult adminSession = login(admin.email(), LocalAccountFixtures.PASSWORD).andReturn();

    fixtures.localProvider(false);

    login(user.email(), LocalAccountFixtures.PASSWORD).andExpect(status().isUnauthorized());
    login(admin.email(), LocalAccountFixtures.PASSWORD).andExpect(status().isOk());
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(userSession)))
        .andExpect(status().isUnauthorized())
        .andExpect(
            header()
                .string(
                    HttpHeaders.WWW_AUTHENTICATE,
                    containsString("error_description=\"local_accounts_disabled\"")));
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(adminSession)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.systemRole").value("SYSTEM_ADMIN"));

    // a regular account's refresh is refused as well (401, cookie cleared), the administrator's not
    mockMvc
        .perform(withCsrf(post(REFRESH), userSession).cookie(refreshCookie(userSession)))
        .andExpect(status().isUnauthorized())
        .andExpect(cookie().maxAge(LocalRefreshCookies.COOKIE_NAME, 0));
    mockMvc
        .perform(withCsrf(post(REFRESH), adminSession).cookie(refreshCookie(adminSession)))
        .andExpect(status().isOk());

    // switching it back on lets the same token through again - nothing was revoked
    fixtures.localProvider(true);
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(userSession)))
        .andExpect(status().isOk());
  }

  @Test
  void aLockedOrExpiredAccountsTokenNamesTheCause() throws Exception {
    MvcResult login = login(user.email(), LocalAccountFixtures.PASSWORD).andReturn();
    LocalCredentials row = fixtures.credentialsOf(user);

    row.lock(LockReason.INACTIVITY, Instant.now(), null);
    fixtures.save(row);
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(login)))
        .andExpect(status().isUnauthorized())
        .andExpect(
            header()
                .string(
                    HttpHeaders.WWW_AUTHENTICATE,
                    containsString("error_description=\"account_locked:inactivity\"")));

    row = fixtures.credentialsOf(user);
    row.unlock(Instant.now());
    row.setExpiresAt(Instant.now().minusSeconds(1), Instant.now());
    fixtures.save(row);
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(login)))
        .andExpect(status().isUnauthorized())
        .andExpect(
            header()
                .string(
                    HttpHeaders.WWW_AUTHENTICATE,
                    containsString("error_description=\"account_expired\"")));
    // and the session cannot be prolonged past the account's end either
    mockMvc
        .perform(withCsrf(post(REFRESH), login).cookie(refreshCookie(login)))
        .andExpect(status().isUnauthorized())
        .andExpect(cookie().maxAge(LocalRefreshCookies.COOKIE_NAME, 0));
  }

  /** Signature and algorithm: only HS256 under the access-token key is a local token. */
  @Test
  void tokensWithoutAValidSignatureUnderTheLocalKeyAreRefused() throws Exception {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(LocalIssuer.URN)
            .subject(user.id().toString())
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .build();
    String unsigned = new com.nimbusds.jwt.PlainJWT(claims).serialize(); // alg=none
    SignedJWT foreignKey = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims);
    foreignKey.sign(new MACSigner(new byte[32]));
    SignedJWT rsa = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims);
    rsa.sign(
        new com.nimbusds.jose.crypto.RSASSASigner(
            new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).generate()));

    for (String token : new String[] {unsigned, foreignKey.serialize(), rsa.serialize()}) {
      mockMvc
          .perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
          .andExpect(status().isUnauthorized())
          .andExpect(
              header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")));
    }
  }

  @Test
  void refreshAndSignOutRequireTheCsrfTokenAndNoOtherEndpointDoes() throws Exception {
    MvcResult login = login(user.email(), LocalAccountFixtures.PASSWORD).andReturn();
    Cookie refresh = refreshCookie(login);
    Cookie xsrf = login.getResponse().getCookie("XSRF-TOKEN");

    // cookie only, no header - refused with its own code, so the SPA tells it from a pcr 403
    mockMvc
        .perform(post(REFRESH).cookie(refresh, xsrf))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_TOKEN_MISSING"))
        .andExpect(jsonPath("$.status").value(403));
    mockMvc.perform(post(LOGOUT).cookie(refresh, xsrf)).andExpect(status().isForbidden());
    // a percent-encoded spelling of the path reaches the same handler and the same check
    mockMvc
        .perform(post(URI.create("/api/v1/auth/local/%72efresh")).cookie(refresh, xsrf))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_TOKEN_MISSING"));
    mockMvc
        .perform(post(URI.create("/api/v1/auth/local/logo%75t")).cookie(refresh, xsrf))
        .andExpect(status().isForbidden());
    // header that does not match the cookie
    mockMvc
        .perform(post(REFRESH).cookie(refresh, xsrf).header("X-XSRF-TOKEN", "falsch"))
        .andExpect(status().isForbidden());
    // the family is still intact - the refused calls changed nothing
    mockMvc.perform(withCsrf(post(REFRESH), login).cookie(refresh)).andExpect(status().isOk());
    // login and change-password never need it (bearer-only, no cookie of their own)
    login(user.email(), LocalAccountFixtures.PASSWORD).andExpect(status().isOk());
    mockMvc
        .perform(
            post(CHANGE_PASSWORD)
                .header(HttpHeaders.AUTHORIZATION, bearer(login))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("currentPassword", "falsch", "newPassword", NEW_PASSWORD))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aValidlySignedTokenOfAnUnknownAccountIsRefusedAndCreatesNothing() throws Exception {
    long usersBefore = users.count();
    UUID unknown = UUID.randomUUID();

    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + signedLocalToken(unknown)))
        .andExpect(status().isUnauthorized())
        .andExpect(
            header()
                .string(
                    HttpHeaders.WWW_AUTHENTICATE,
                    containsString("error_description=\"unknown_account\"")));

    assertThat(users.count()).isEqualTo(usersBefore);
    assertThat(users.findBySubjectAndIssuer(unknown.toString(), LocalIssuer.URN)).isEmpty();
  }

  @Test
  void tokensOfOtherIssuersStillTakeTheOidcPath() throws Exception {
    mockMvc
        .perform(
            get(ME)
                .header(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer " + signedToken(user.id(), "https://idp.example/realms/fremd")))
        .andExpect(status().isUnauthorized())
        .andExpect(
            header()
                .string(
                    HttpHeaders.WWW_AUTHENTICATE,
                    containsString("error_description=\"unknown_issuer\"")));
  }

  @Test
  void theSignInPageSeesTheSwitchButNeverTheLocalRowAmongTheProviders() throws Exception {
    mockMvc
        .perform(get("/api/v1/auth/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("oidc"))
        .andExpect(jsonPath("$.providers").isEmpty())
        .andExpect(jsonPath("$.localAccounts.enabled").value(true))
        .andExpect(jsonPath("$.localAccounts.passwordMinLength").value(12))
        .andExpect(jsonPath("$.localAccounts.selfRegistrationEnabled").value(false))
        .andExpect(jsonPath("$.localAccounts.passwordResetEnabled").value(false));

    fixtures.localProvider(false);
    mockMvc
        .perform(get("/api/v1/auth/config"))
        .andExpect(jsonPath("$.localAccounts.enabled").value(false));
  }

  /**
   * ADR-0033, Entscheidung 8 asks for the cost of the revocation check per request. The validator
   * adds one primary-key lookup on {@code local_credentials} and a Caffeine hit; this measures the
   * whole {@code /auth/me} round trip through MockMvc so the number in the PR has a source.
   */
  @Test
  void theRevocationCheckIsOnePrimaryKeyLookupPerRequest() throws Exception {
    MvcResult login = login(user.email(), LocalAccountFixtures.PASSWORD).andReturn();
    String bearer = bearer(login);
    for (int i = 0; i < 5; i++) {
      mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isOk());
    }

    int rounds = 50;
    long start = System.nanoTime();
    for (int i = 0; i < rounds; i++) {
      mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isOk());
    }
    long perRequestMicros = (System.nanoTime() - start) / rounds / 1_000;

    LoggerFactory.getLogger(LocalAuthFlowIntegrationTest.class)
        .info(
            "GET /api/v1/auth/me with a local token: {} µs per request over {} rounds (MockMvc,"
                + " Testcontainers Postgres, including the provisioning filter's own lookup)",
            perRequestMicros,
            rounds);
    assertThat(perRequestMicros).isLessThan(Duration.ofSeconds(1).toNanos() / 1_000);
  }

  private org.springframework.test.web.servlet.ResultActions login(String email, String password)
      throws Exception {
    return mockMvc.perform(
        post(LOGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("email", email, "password", password))));
  }

  /** The CSRF double submit: the cookie the sign-in set, echoed as the header. */
  private static MockHttpServletRequestBuilder withCsrf(
      MockHttpServletRequestBuilder request, MvcResult source) {
    Cookie xsrf = source.getResponse().getCookie("XSRF-TOKEN");
    assertThat(xsrf).as("XSRF-TOKEN cookie of the sign-in response").isNotNull();
    return request.cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue());
  }

  private static Cookie refreshCookie(MvcResult result) {
    Cookie cookie = result.getResponse().getCookie(LocalRefreshCookies.COOKIE_NAME);
    assertThat(cookie).as("opaa_refresh cookie").isNotNull();
    return cookie;
  }

  private static String bearer(MvcResult result) throws Exception {
    String token = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    return "Bearer " + token;
  }

  /** The body with the timestamp removed, so two answers can be compared for equality. */
  private static String body(org.springframework.test.web.servlet.ResultActions actions)
      throws Exception {
    return actions
        .andReturn()
        .getResponse()
        .getContentAsString()
        .replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"-\"");
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

  private String signedLocalToken(UUID subject) throws Exception {
    return signedToken(subject, LocalIssuer.URN);
  }

  private String signedToken(UUID subject, String issuer) throws Exception {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(subject.toString())
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .claim("email", "fremd@stadt.example")
            .claim("name", "Fremd")
            .claim("pcr", false)
            .build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(com.nimbusds.jose.JOSEObjectType.JWT)
                .build(),
            claims);
    jwt.sign(new MACSigner(keys.key(Purpose.ACCESS_TOKEN)));
    return jwt.serialize();
  }

  private long auditCount(String eventType) {
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_log WHERE event_type = ?", Long.class, eventType);
    return count == null ? 0 : count;
  }

  private static ListAppender<ILoggingEvent> attachTo(Class<?> loggerClass) {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(loggerClass)).addAppender(appender);
    return appender;
  }
}
