package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthMockMvcTest;
import jakarta.servlet.http.Cookie;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * No secret and no person in the logs (ADR-0033, Entscheidungen 9 and 13): the whole local flow -
 * sign-in, wrong password, refresh, replay, password change, sign-out - writes no raw refresh
 * token, no access token and no password into any log line at any level (the request DTOs mask
 * their password fields in {@code toString()}, which Spring MVC's TRACE body dump prints), no
 * address into a line of OPAA's own loggers at any level nor into any line at the production level
 * INFO and above (Spring MVC's own DEBUG/TRACE request dump echoes the request body as a whole and
 * is a framework diagnostic no deployment runs), and no {@code LOCAL_*} audit event carries the
 * address or the display name in any of its text columns. The later sub-issues (seed, invitations,
 * lockout) extend the flow here.
 */
@OpaaLocalAuthMockMvcTest
class LocalAuthLogPrivacyIntegrationTest {

  private static final String NEW_PASSWORD = "neues-sicheres-passwort-2026";

  @Autowired private MockMvc mockMvc;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private JdbcTemplate jdbc;

  private LocalAccountFixtures fixtures;
  private LocalAccount user;
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private Logger root;
  private Level previousLevel;

  @BeforeEach
  void setUp() {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    fixtures.localProvider(true);
    user = fixtures.activeUser("erika-" + UUID.randomUUID() + "@stadt.example");
    root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    previousLevel = root.getLevel();
    root.setLevel(Level.TRACE);
    logs.start();
    root.addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    root.detachAppender(logs);
    root.setLevel(previousLevel);
    fixtures.cleanUp();
  }

  @Test
  void theWholeLocalFlowLeaksNoSecretAndNoPersonIntoLogsOrAuditRows() throws Exception {
    MvcResult login = login(user.email(), LocalAccountFixtures.PASSWORD, 200);
    login(user.email(), "falsches-passwort", 401);
    Cookie first = login.getResponse().getCookie(LocalRefreshCookies.COOKIE_NAME);
    MvcResult refreshed =
        mockMvc
            .perform(withCsrf(post("/api/v1/auth/local/refresh"), login).cookie(first))
            .andExpect(status().isOk())
            .andReturn();
    Cookie second = refreshed.getResponse().getCookie(LocalRefreshCookies.COOKIE_NAME);
    // replay
    mockMvc
        .perform(withCsrf(post("/api/v1/auth/local/refresh"), login).cookie(first))
        .andExpect(status().isUnauthorized());
    MvcResult again = login(user.email(), LocalAccountFixtures.PASSWORD, 200);
    MvcResult changed =
        mockMvc
            .perform(
                post("/api/v1/auth/local/change-password")
                    .header(HttpHeaders.AUTHORIZATION, bearer(again))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json(
                            Map.of(
                                "currentPassword",
                                LocalAccountFixtures.PASSWORD,
                                "newPassword",
                                NEW_PASSWORD))))
            .andExpect(status().isOk())
            .andReturn();
    Cookie third = changed.getResponse().getCookie(LocalRefreshCookies.COOKIE_NAME);
    mockMvc
        .perform(
            withCsrf(post("/api/v1/auth/local/logout"), changed)
                .cookie(third)
                .header(HttpHeaders.AUTHORIZATION, bearer(changed)))
        .andExpect(status().isNoContent());

    List<String> secrets =
        List.of(
            first.getValue(),
            second.getValue(),
            third.getValue(),
            LocalAccountFixtures.PASSWORD,
            NEW_PASSWORD,
            "falsches-passwort",
            JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken"));
    List<String> personal = List.of(user.email(), user.email().toUpperCase());
    assertThat(logs.list).isNotEmpty();
    assertThat(logs.list).anyMatch(event -> event.getLoggerName().startsWith("io.opaa"));
    for (ILoggingEvent event : logs.list) {
      String line = event.getFormattedMessage() + " " + String.valueOf(event.getArgumentArray());
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

    List<Map<String, Object>> events =
        jdbc.queryForList(
            "SELECT event_type, actor_ref, object_label, subject_ref, before, after, reason"
                + " FROM audit_log WHERE event_type LIKE 'LOCAL_%'");
    assertThat(events)
        .extracting(row -> (String) row.get("event_type"))
        .contains("LOCAL_SESSION_REVOKED", "LOCAL_PASSWORD_CHANGED");
    for (Map<String, Object> row : events) {
      String text = String.valueOf(row.values());
      assertThat(text)
          .as("audit row %s", row.get("event_type"))
          .doesNotContain(user.email())
          .doesNotContain(LocalAccountFixtures.DISPLAY_NAME)
          .doesNotContain(user.id().toString());
    }
  }

  private MvcResult login(String email, String password, int expectedStatus) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/auth/local/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", email, "password", password))))
        .andExpect(status().is(expectedStatus))
        .andReturn();
  }

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
      withCsrf(
          org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
          MvcResult source) {
    Cookie xsrf = source.getResponse().getCookie("XSRF-TOKEN");
    return request.cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue());
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
