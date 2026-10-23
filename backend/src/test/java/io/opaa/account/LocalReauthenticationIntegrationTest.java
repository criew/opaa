package io.opaa.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.SignedJWT;
import io.opaa.auth.RecentLoginInterceptor;
import io.opaa.auth.RecentLoginPolicy;
import io.opaa.auth.RecentLoginProperties;
import io.opaa.common.AccessDeniedException;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthMockMvcTest;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The recent sign-in of a local account through the production {@code oidc} filter chain against
 * Postgres: a sign-in and a confirmation with the password yield a token that passes {@link
 * RecentLoginPolicy}, a refreshed one never does, a token past the window does not either, and a
 * wrong password at the confirmation is refused and counted towards the lockout. The policy is held
 * against the claims of the tokens the endpoints issue; that it guards a marked handler is {@code
 * RecentLoginInterceptorTest}'s subject, that the interceptor sits in front of every handler here.
 */
@OpaaLocalAuthMockMvcTest
class LocalReauthenticationIntegrationTest {

  private static final String LOGIN = "/api/v1/auth/local/login";
  private static final String REFRESH = "/api/v1/auth/local/refresh";
  private static final String REAUTHENTICATE = "/api/v1/auth/local/reauthenticate";
  private static final String ME = "/api/v1/auth/me";

  @Autowired private MockMvc mockMvc;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private RecentLoginPolicy policy;
  @Autowired private RecentLoginProperties properties;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping handlerMapping;

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
  void theConfiguredWindowIsFiveMinutesAndGuardsEveryHandler() throws Exception {
    assertThat(properties.maxAge()).isEqualTo(Duration.ofMinutes(5));
    HandlerExecutionChain chain =
        handlerMapping.getHandler(new MockHttpServletRequest("GET", "/api/v1/auth/me"));

    assertThat(chain).isNotNull();
    assertThat(chain.getInterceptorList()).hasAtLeastOneElementOfType(RecentLoginInterceptor.class);
  }

  @Test
  void aSignInCountsAsRecentButARefreshedTokenDoesNot() throws Exception {
    MvcResult login = login().andExpect(status().isOk()).andReturn();
    assertThatCode(() -> policy.requireRecentLogin(authenticationOf(login)))
        .doesNotThrowAnyException();

    MvcResult refreshed =
        mockMvc
            .perform(withCsrf(post(REFRESH), login).cookie(refreshCookie(login)))
            .andExpect(status().isOk())
            .andReturn();

    // the refreshed token is valid for everything else ...
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(refreshed)))
        .andExpect(status().isOk());
    // ... but carries no sign-in, even seconds after one
    assertRefused(policy, authenticationOf(refreshed), "AUTH_TIME_MISSING");
  }

  @Test
  void aSignInPastTheWindowIsNoLongerRecent() throws Exception {
    MvcResult login = login().andReturn();
    RecentLoginPolicy later =
        new RecentLoginPolicy(
            properties,
            Clock.fixed(Instant.now().plus(properties.maxAge()).plusSeconds(61), ZoneOffset.UTC));

    assertRefused(later, authenticationOf(login), "AUTH_TIME_TOO_OLD");
  }

  @Test
  void theRightPasswordConfirmsTheSignInWithoutTouchingTheRefreshCookie() throws Exception {
    MvcResult login = login().andReturn();
    MvcResult refreshed =
        mockMvc.perform(withCsrf(post(REFRESH), login).cookie(refreshCookie(login))).andReturn();

    MvcResult confirmed =
        reauthenticate(refreshed, LocalAccountFixtures.PASSWORD)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isString())
            .andExpect(jsonPath("$.passwordChangeRequired").value(false))
            .andExpect(cookie().doesNotExist(LocalRefreshCookies.COOKIE_NAME))
            .andReturn();

    assertThatCode(() -> policy.requireRecentLogin(authenticationOf(confirmed)))
        .doesNotThrowAnyException();
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(confirmed)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(user.id().toString()));
  }

  @Test
  void aWrongPasswordIsRefusedAndCountedAsAFailedAttempt() throws Exception {
    MvcResult login = login().andReturn();
    int before = fixtures.credentialsOf(user).getFailedLoginAttempts();

    reauthenticate(login, "falsches-passwort-2026")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("password"))
        .andExpect(jsonPath("$.fieldErrors[0].code").value(LocalPasswordService.WRONG_PASSWORD))
        .andExpect(jsonPath("$.accessToken").doesNotExist());

    assertThat(fixtures.credentialsOf(user).getFailedLoginAttempts()).isEqualTo(before + 1);
  }

  @Test
  void theConfirmationNeedsASession() throws Exception {
    mockMvc
        .perform(
            post(REAUTHENTICATE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + LocalAccountFixtures.PASSWORD + "\"}"))
        .andExpect(status().isUnauthorized());
  }

  private ResultActions login() throws Exception {
    return mockMvc.perform(
        post(LOGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"email\":\""
                    + user.email()
                    + "\",\"password\":\""
                    + LocalAccountFixtures.PASSWORD
                    + "\"}"));
  }

  private ResultActions reauthenticate(MvcResult session, String password) throws Exception {
    return mockMvc.perform(
        post(REAUTHENTICATE)
            .header(HttpHeaders.AUTHORIZATION, bearer(session))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"password\":\"" + password + "\"}"));
  }

  private static void assertRefused(
      RecentLoginPolicy policy, Authentication authentication, String reason) {
    assertThatThrownBy(() -> policy.requireRecentLogin(authentication))
        .isInstanceOfSatisfying(
            AccessDeniedException.class,
            denied -> {
              assertThat(denied.getCode()).isEqualTo(RecentLoginPolicy.CODE);
              assertThat(denied.getReason()).isEqualTo(reason);
            });
  }

  /**
   * The claims of the issued token as the resource server hands them on; signature checked by /me.
   */
  private static Authentication authenticationOf(MvcResult result) throws Exception {
    String token = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    Map<String, Object> claims = SignedJWT.parse(token).getJWTClaimsSet().toJSONObject();
    Jwt jwt =
        new Jwt(
            token,
            Instant.now().minusSeconds(1),
            Instant.now().plusSeconds(600),
            Map.of("alg", "HS256"),
            claims);
    return new JwtAuthenticationToken(jwt, List.of());
  }

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
      withCsrf(
          org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
          MvcResult source) {
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
    return "Bearer " + JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
  }
}
