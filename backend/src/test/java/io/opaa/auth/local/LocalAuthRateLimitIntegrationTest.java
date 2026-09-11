package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.common.TooManyRequestsException;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthMockMvcTest;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The limits of the local sign-in through the production {@code oidc} chain (ADR-0033, Entscheidung
 * 9): the eleventh sign-in from one address within a minute is {@code 429} with {@code
 * Retry-After}; a forged {@code X-Forwarded-For} from an untrusted connection does not change the
 * bucket, behind a trusted proxy the header names the client; the refresh and the password change
 * have their own budgets; and the diagnostics endpoint shows the address exactly as it was resolved
 * for that request.
 */
// Own context (AGENTS.md, "Spring-Testkontexte"): the shared oidc context widens the local-auth
// limits so its many sign-ins from one address pass; this class needs the production defaults
// and a trusted proxy range to prove the limits and the header resolution themselves.
@OpaaLocalAuthMockMvcTest
@TestPropertySource(
    properties = {
      "opaa.rate-limit.trusted-proxy-cidrs=10.0.0.0/8",
      "opaa.rate-limit.local-auth.login.max-requests=10",
      "opaa.rate-limit.local-auth.login.window-seconds=60",
      "opaa.rate-limit.local-auth.login.global-max-requests=1000",
      "opaa.rate-limit.local-auth.refresh.max-requests=3",
      "opaa.rate-limit.local-auth.change-password.max-requests=5",
      "opaa.rate-limit.local-auth.change-password.window-seconds=300"
    })
class LocalAuthRateLimitIntegrationTest {

  private static final String LOGIN = "/api/v1/auth/local/login";
  private static final String REFRESH = "/api/v1/auth/local/refresh";
  private static final String CHANGE_PASSWORD = "/api/v1/auth/local/change-password";
  private static final String DIAGNOSTICS = "/api/v1/admin/diagnostics/client-address";

  /** Throttled attempts name no account: a known one would be locked after five (#1535). */
  private static final String UNKNOWN = "niemand@stadt.example";

  @Autowired private MockMvc mockMvc;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;

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
  void theEleventhSignInFromOneAddressWithinAMinuteIs429WithRetryAfter() throws Exception {
    for (int i = 0; i < 10; i++) {
      login(UNKNOWN, "falsches-passwort", from("203.0.113.11"))
          .andExpect(status().isUnauthorized());
    }

    login(UNKNOWN, "falsches-passwort", from("203.0.113.11"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
        .andExpect(jsonPath("$.status").value(429))
        .andExpect(jsonPath("$.error").value(TooManyRequestsException.MESSAGE));
    // the right password does not help while the address is throttled ...
    MvcResult throttled =
        login(user.email(), LocalAccountFixtures.PASSWORD, from("203.0.113.11"))
            .andExpect(status().isTooManyRequests())
            .andReturn();
    long retryAfter = Long.parseLong(throttled.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
    assertThat(retryAfter).isBetween(1L, 60L);
    // ... and another address has its own budget
    login(user.email(), LocalAccountFixtures.PASSWORD, from("203.0.113.12"))
        .andExpect(status().isOk());
  }

  @Test
  void neitherAForwardedPrefixNorAPercentEncodedPathReachesTheHandlerPastTheRule()
      throws Exception {
    for (int i = 0; i < 10; i++) {
      login(UNKNOWN, "falsches-passwort", from("203.0.113.71"))
          .andExpect(status().isUnauthorized());
    }

    // X-Forwarded-Prefix: Spring moves the prefix into the context path; the handler still runs
    mockMvc
        .perform(
            post(LOGIN)
                .with(from("203.0.113.71"))
                .header("X-Forwarded-Prefix", "/x")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", UNKNOWN, "password", "falsches-passwort"))))
        .andExpect(status().isTooManyRequests());
    mockMvc
        .perform(
            post(URI.create("/api/v1/auth/local/%6Cogin"))
                .with(from("203.0.113.71"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", UNKNOWN, "password", "falsches-passwort"))))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void aForgedForwardedForHeaderFromAnUntrustedConnectionDoesNotChangeTheBucket() throws Exception {
    for (int i = 0; i < 10; i++) {
      login(UNKNOWN, "falsches-passwort", from("203.0.113.21", "198.51.100." + (i + 1)))
          .andExpect(status().isUnauthorized());
    }

    login(UNKNOWN, "falsches-passwort", from("203.0.113.21", "198.51.100.99"))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void behindATrustedProxyTheClientIsTheAddressTheProxyAppended() throws Exception {
    for (int i = 0; i < 10; i++) {
      // an attacker's own "9.9.9.9" prefix never rotates the bucket: the proxy appended the client
      login(UNKNOWN, "falsches-passwort", from("10.0.0.5", "9.9.9." + i + ", 198.51.100.31"))
          .andExpect(status().isUnauthorized());
    }

    login(UNKNOWN, "falsches-passwort", from("10.0.0.5", "198.51.100.31"))
        .andExpect(status().isTooManyRequests());
    // a different client behind the same proxy has its own budget
    login(user.email(), LocalAccountFixtures.PASSWORD, from("10.0.0.5", "198.51.100.32"))
        .andExpect(status().isOk());
  }

  @Test
  void theRefreshHasItsOwnBudget() throws Exception {
    Cookie garbage = new Cookie(LocalRefreshCookies.COOKIE_NAME, "kein-token");
    for (int i = 0; i < 3; i++) {
      mockMvc
          .perform(post(REFRESH).cookie(garbage).with(from("203.0.113.41")))
          .andExpect(status().is(403));
    }

    mockMvc
        .perform(post(REFRESH).cookie(garbage).with(from("203.0.113.41")))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
  }

  @Test
  void theSixthPasswordChangeAttemptOfOneAccountIs429AndAnotherAccountIsNotAffected()
      throws Exception {
    LocalAccount other = fixtures.activeUser("max-" + UUID.randomUUID() + "@stadt.example");
    String bearer =
        bearer(
            login(user.email(), LocalAccountFixtures.PASSWORD, from("203.0.113.51")).andReturn());
    String otherBearer =
        bearer(
            login(other.email(), LocalAccountFixtures.PASSWORD, from("203.0.113.52")).andReturn());

    for (int i = 0; i < 5; i++) {
      changePassword(bearer, "falsches-passwort", from("203.0.113.5" + i))
          .andExpect(status().isBadRequest());
    }

    changePassword(bearer, LocalAccountFixtures.PASSWORD, from("203.0.113.59"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
        .andExpect(jsonPath("$.error").value(TooManyRequestsException.MESSAGE));
    changePassword(otherBearer, "falsches-passwort", from("203.0.113.59"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void theDiagnosticsEndpointShowsTheAddressAsResolvedForThatRequest() throws Exception {
    LocalAccount admin = fixtures.activeAdmin("admin-" + UUID.randomUUID() + "@stadt.example");
    String adminBearer =
        bearer(
            login(admin.email(), LocalAccountFixtures.PASSWORD, from("203.0.113.61")).andReturn());

    mockMvc
        .perform(
            get(DIAGNOSTICS)
                .header(HttpHeaders.AUTHORIZATION, adminBearer)
                .with(from("10.0.0.5", "9.9.9.9, 198.51.100.61")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.clientAddress").value("198.51.100.61"))
        .andExpect(jsonPath("$.remoteAddress").value("10.0.0.5"))
        .andExpect(jsonPath("$.forwardedFor").value("9.9.9.9, 198.51.100.61"))
        .andExpect(jsonPath("$.forwardedForTrusted").value(true))
        .andExpect(jsonPath("$.trustedProxiesConfigured").value(true));
    mockMvc
        .perform(
            get(DIAGNOSTICS)
                .header(HttpHeaders.AUTHORIZATION, adminBearer)
                .with(from("203.0.113.62", "198.51.100.61")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.clientAddress").value("203.0.113.62"))
        .andExpect(jsonPath("$.forwardedForTrusted").value(false));

    String userBearer =
        bearer(
            login(user.email(), LocalAccountFixtures.PASSWORD, from("203.0.113.63")).andReturn());
    mockMvc
        .perform(get(DIAGNOSTICS).header(HttpHeaders.AUTHORIZATION, userBearer))
        .andExpect(status().isForbidden());
  }

  private org.springframework.test.web.servlet.ResultActions login(
      String email, String password, RequestPostProcessor origin) throws Exception {
    return mockMvc.perform(
        post(LOGIN)
            .with(origin)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("email", email, "password", password))));
  }

  private org.springframework.test.web.servlet.ResultActions changePassword(
      String bearer, String currentPassword, RequestPostProcessor origin) throws Exception {
    MockHttpServletRequestBuilder request =
        post(CHANGE_PASSWORD)
            .with(origin)
            .header(HttpHeaders.AUTHORIZATION, bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                json(
                    Map.of(
                        "currentPassword",
                        currentPassword,
                        "newPassword",
                        "neues-sicheres-passwort-2026")));
    return mockMvc.perform(request);
  }

  private static RequestPostProcessor from(String remoteAddr) {
    return from(remoteAddr, null);
  }

  private static RequestPostProcessor from(String remoteAddr, String forwardedFor) {
    return request -> {
      request.setRemoteAddr(remoteAddr);
      if (forwardedFor != null) {
        request.addHeader("X-Forwarded-For", forwardedFor);
      }
      return request;
    };
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
