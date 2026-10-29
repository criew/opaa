package io.opaa.externalaccess.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.account.LocalRefreshCookies;
import io.opaa.auth.RecentLoginPolicy;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthMockMvcTest;
import jakarta.servlet.http.Cookie;
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

/**
 * The issuance of an access token through the production {@code oidc} filter chain: a refreshed
 * access token - valid for everything else - is refused with {@code RECENT_LOGIN_REQUIRED} before
 * the request is even read, a token of a sign-in gets through to the validation of the request.
 */
@OpaaLocalAuthMockMvcTest
class ExternalAccessTokenRecentLoginIntegrationTest {

  private static final String TOKENS = "/api/v1/external-access/tokens";

  @Autowired private MockMvc mockMvc;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private JdbcTemplate jdbc;

  private LocalAccountFixtures fixtures;
  private LocalAccount user;

  @BeforeEach
  void setUp() {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    fixtures.localProvider(true);
    user = fixtures.activeUser("token-" + UUID.randomUUID() + "@stadt.example");
  }

  @AfterEach
  void tearDown() {
    fixtures.cleanUp();
  }

  @Test
  void aRefreshedTokenIssuesNoAccessTokenButASignInGetsThrough() throws Exception {
    MvcResult login = login();
    Cookie xsrf = login.getResponse().getCookie("XSRF-TOKEN");
    MvcResult refreshed =
        mockMvc
            .perform(
                post("/api/v1/auth/local/refresh")
                    .cookie(xsrf, login.getResponse().getCookie(LocalRefreshCookies.COOKIE_NAME))
                    .header("X-XSRF-TOKEN", xsrf.getValue()))
            .andExpect(status().isOk())
            .andReturn();

    mockMvc
        .perform(issuance().header(HttpHeaders.AUTHORIZATION, bearer(refreshed)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(RecentLoginPolicy.CODE));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM external_access_tokens WHERE user_id = ?",
                Long.class,
                user.id()))
        .isZero();

    // An empty request: past the recent-login check, the validation answers - nothing is issued.
    mockMvc
        .perform(issuance().header(HttpHeaders.AUTHORIZATION, bearer(login)))
        .andExpect(status().isBadRequest());
  }

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
      issuance() {
    return post(TOKENS).contentType(MediaType.APPLICATION_JSON).content("{}");
  }

  private MvcResult login() throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/auth/local/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\""
                        + user.email()
                        + "\",\"password\":\""
                        + LocalAccountFixtures.PASSWORD
                        + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
  }

  private static String bearer(MvcResult result) throws Exception {
    return "Bearer " + JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
  }
}
