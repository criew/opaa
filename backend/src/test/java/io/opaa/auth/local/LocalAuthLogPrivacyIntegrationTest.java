package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * address or the display name in any of its text columns. The seed's one-time password (#1534) is
 * the one deliberate exception and stays inside its marked block. The later sub-issues
 * (invitations, lockout) extend the flow here.
 */
@OpaaLocalAuthMockMvcTest
class LocalAuthLogPrivacyIntegrationTest {

  private static final String NEW_PASSWORD = "neues-sicheres-passwort-2026";

  @Autowired private MockMvc mockMvc;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private LocalAdminSeeder seeder;
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
      String line = event.getFormattedMessage() + " " + throwableText(event);
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

  /** Message and stack-trace head of a logged exception - they are part of the line. */
  private static String throwableText(ILoggingEvent event) {
    IThrowableProxy proxy = event.getThrowableProxy();
    if (proxy == null) {
      return "";
    }
    StringBuilder text = new StringBuilder(String.valueOf(proxy.getMessage()));
    StackTraceElementProxy[] frames = proxy.getStackTraceElementProxyArray();
    for (int i = 0; frames != null && i < Math.min(5, frames.length); i++) {
      text.append(' ').append(frames[i].getSTEAsString());
    }
    return text.toString();
  }

  /**
   * The seed's one exception (ADR-0033, Entscheidung 5): the bootstrap administrator's one-time
   * password stands in exactly one log event - the marked block - and nowhere else; the address
   * stands in no line of OPAA's loggers, and neither {@code LOCAL_ADMIN_RESET} nor the bootstrap
   * sign-in event {@code LOCAL_BOOTSTRAP_ACCOUNT_LOGIN} carries address, name or account id.
   * Exercised through the restart path, the one that runs against an existing account in this
   * shared context (the seed itself refuses the context's default address on purpose).
   */
  @Test
  void theBootstrapPasswordStandsOnlyInItsMarkedBlockAndTheAddressNowhere() throws Exception {
    LocalAccount bootstrap =
        fixtures.activeAdmin("notanker-" + UUID.randomUUID() + "@stadt.example");
    LocalCredentials row = fixtures.credentialsOf(bootstrap);
    row.markBootstrap();
    fixtures.save(row);
    try {
      assertThat(seeder.restoreBootstrapAdmin()).isEqualTo(LocalAdminSeeder.Outcome.RESET);
      List<ILoggingEvent> blocks =
          logs.list.stream()
              .filter(event -> event.getFormattedMessage().contains("EINMALIGE AUSGABE"))
              .toList();
      assertThat(blocks).hasSize(1);
      Matcher matcher =
          Pattern.compile("Passwort:\\s+(\\S+)").matcher(blocks.getFirst().getFormattedMessage());
      assertThat(matcher.find()).isTrue();
      String password = matcher.group(1);
      MvcResult login = login(bootstrap.email(), password, 200);
      String token = JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");

      List<String> personal = List.of(bootstrap.email(), bootstrap.email().toUpperCase());
      for (ILoggingEvent event : logs.list) {
        String line = event.getFormattedMessage() + " " + throwableText(event);
        String where = "log line of " + event.getLoggerName() + " at " + event.getLevel();
        if (event != blocks.getFirst()) {
          assertThat(line).as(where).doesNotContain(password);
        }
        assertThat(line).as(where).doesNotContain(token);
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
                  + " FROM audit_log WHERE event_type IN"
                  + " ('LOCAL_ADMIN_RESET', 'LOCAL_BOOTSTRAP_ACCOUNT_LOGIN')");
      assertThat(events)
          .extracting(r -> (String) r.get("event_type"))
          .contains("LOCAL_ADMIN_RESET", "LOCAL_BOOTSTRAP_ACCOUNT_LOGIN");
      for (Map<String, Object> r : events) {
        String text = String.valueOf(r.values());
        assertThat(text)
            .as("audit row %s", r.get("event_type"))
            .doesNotContain(bootstrap.email())
            .doesNotContain(LocalAccountFixtures.DISPLAY_NAME)
            .doesNotContain(bootstrap.id().toString())
            .doesNotContain(password);
      }
    } finally {
      jdbc.update(
          "DELETE FROM audit_log WHERE event_type IN"
              + " ('LOCAL_ADMIN_RESET', 'LOCAL_BOOTSTRAP_ACCOUNT_LOGIN')");
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
