package io.opaa.selfdisclosure;

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
 * The ticket of the self-disclosure through the production {@code oidc} filter chain: a token of a
 * sign-in gets one, a refreshed token - valid for everything else - is refused with {@code
 * RECENT_LOGIN_REQUIRED}, and nothing is reserved for the refused call.
 */
@OpaaLocalAuthMockMvcTest
class SelfDisclosureRecentLoginIntegrationTest {

  private static final String TICKETS = "/api/v1/me/self-disclosure/tickets";

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
    user = fixtures.activeUser("erika-" + UUID.randomUUID() + "@stadt.example");
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM self_disclosure_usage WHERE account_id = ?", user.id());
    fixtures.cleanUp();
  }

  @Test
  void aRefreshedTokenGetsNoTicketButASignInDoes() throws Exception {
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
        .perform(post(TICKETS).header(HttpHeaders.AUTHORIZATION, bearer(refreshed)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(RecentLoginPolicy.CODE));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM self_disclosure_usage WHERE account_id = ?",
                Long.class,
                user.id()))
        .isZero();

    mockMvc
        .perform(post(TICKETS).header(HttpHeaders.AUTHORIZATION, bearer(login)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.ticket").isString());
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
