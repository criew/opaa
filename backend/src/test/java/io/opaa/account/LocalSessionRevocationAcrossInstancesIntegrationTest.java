package io.opaa.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.auth.LocalCredentials;
import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.auth.OidcProviderRegistry;
import io.opaa.auth.UserRepository;
import io.opaa.security.LocalAuthKeyService;
import io.opaa.security.LocalAuthKeyService.Purpose;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthMockMvcTest;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Ending sessions holds across instances (ADR-0053, Entscheidung 6). This context is instance A;
 * instance B is a second set of the revocation's stateful parts over the same database - its own
 * denylist cache and validator, and a token service whose clock runs ahead. A sign-out on A is
 * refused on B from the next request on; a password change, a lock and a detected reuse end a token
 * B minted with its clock ahead, because the token carries the session generation instead of being
 * compared by time.
 */
@OpaaLocalAuthMockMvcTest
class LocalSessionRevocationAcrossInstancesIntegrationTest {

  private static final String LOGIN = "/api/v1/auth/local/login";
  private static final String REFRESH = "/api/v1/auth/local/refresh";
  private static final String LOGOUT = "/api/v1/auth/local/logout";
  private static final String ME = "/api/v1/auth/me";
  private static final Duration CLOCK_AHEAD = Duration.ofSeconds(5);

  @Autowired private MockMvc mockMvc;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private LocalAuthKeyService keys;
  @Autowired private LocalAuthProperties properties;
  @Autowired private LocalRevokedTokenRepository revokedTokens;
  @Autowired private LocalRefreshTokenRepository refreshTokens;
  @Autowired private LocalCredentialsRepository credentials;
  @Autowired private UserRepository users;
  @Autowired private OidcProviderRegistry registry;
  @Autowired private LocalPasswordService passwords;
  @Autowired private LocalUserService localUsers;
  @Autowired private Clock clock;

  private LocalAccountFixtures fixtures;
  private LocalAccount account;

  @BeforeEach
  void setUp() {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    fixtures.localProvider(true);
    account = fixtures.activeUser("erika-" + UUID.randomUUID() + "@stadt.example");
  }

  @AfterEach
  void tearDown() {
    fixtures.cleanUp();
  }

  @Test
  void aSignOutOnAIsRefusedOnBFromTheNextRequestOn() throws Exception {
    MvcResult login = login();
    String token = accessToken(login);
    LocalTokenValidator validatorOfB =
        new LocalTokenValidator(
            credentials,
            users,
            refreshTokens,
            new LocalTokenRevocationService(revokedTokens, credentials, properties, clock),
            registry,
            clock);
    assertThat(validatorOfB.rejectionFor(decode(token))).isEmpty();

    mockMvc
        .perform(withCsrf(post(LOGOUT), login).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isNoContent());

    assertThat(validatorOfB.rejectionFor(decode(token)))
        .hasValueSatisfying(
            rejection ->
                assertThat(rejection.marker()).isEqualTo(LocalTokenMarkers.SESSION_REVOKED));
  }

  @Test
  void aPasswordChangeEndsATokenMintedOnAnInstanceWhoseClockIsAhead() throws Exception {
    String token = mintedOnB();
    expectAccepted(token);

    passwords.changePassword(
        account.user(), LocalAccountFixtures.PASSWORD, "neues-sicheres-passwort-2026");

    expectRefused(token);
  }

  @Test
  void aLockEndsATokenMintedOnAnInstanceWhoseClockIsAheadAlsoAfterTheUnlock() throws Exception {
    String token = mintedOnB();
    expectAccepted(token);

    localUsers.lockForInactivity(account.id());
    LocalCredentials locked = fixtures.credentialsOf(account);
    locked.unlock(clock.instant());
    fixtures.save(locked);

    expectRefused(token);
  }

  @Test
  void aDetectedReuseEndsATokenMintedOnAnInstanceWhoseClockIsAhead() throws Exception {
    MvcResult login = login();
    Cookie refreshCookie = login.getResponse().getCookie(LocalRefreshCookies.COOKIE_NAME);
    String token = mintedOnB();
    expectAccepted(token);
    mockMvc
        .perform(withCsrf(post(REFRESH), login).cookie(refreshCookie))
        .andExpect(status().isOk());

    mockMvc
        .perform(withCsrf(post(REFRESH), login).cookie(refreshCookie))
        .andExpect(status().isUnauthorized());

    expectRefused(token);
  }

  /** A token of the account as instance B mints it, with its clock ahead of A's. */
  private String mintedOnB() {
    LocalAccessTokenService tokensOfB =
        new LocalAccessTokenService(keys, properties, Clock.offset(clock, CLOCK_AHEAD));
    return tokensOfB.issue(account.user(), fixtures.credentialsOf(account)).value();
  }

  private void expectAccepted(String token) throws Exception {
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk());
  }

  private void expectRefused(String token) throws Exception {
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  private MvcResult login() throws Exception {
    return mockMvc
        .perform(
            post(LOGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\""
                        + account.email()
                        + "\",\"password\":\""
                        + LocalAccountFixtures.PASSWORD
                        + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
  }

  /** The CSRF double submit: the cookie the sign-in set, echoed as the header. */
  private static MockHttpServletRequestBuilder withCsrf(
      MockHttpServletRequestBuilder request, MvcResult login) {
    Cookie xsrf = login.getResponse().getCookie("XSRF-TOKEN");
    return request.cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue());
  }

  private static String accessToken(MvcResult login) throws Exception {
    return JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
  }

  private Jwt decode(String token) {
    return NimbusJwtDecoder.withSecretKey(keys.key(Purpose.ACCESS_TOKEN))
        .macAlgorithm(MacAlgorithm.HS256)
        .build()
        .decode(token);
  }
}
