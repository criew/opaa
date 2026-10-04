package io.opaa.connection.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import com.jayway.jsonpath.JsonPath;
import io.opaa.auth.DevAuthFilter;
import io.opaa.connection.token.NewSecret;
import io.opaa.indexing.source.profileprobe.PersonProbeSourceConnector;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.OpaaIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Acceptance criterion of #2163: a person's secret and account name appear in no log line, no
 * answer of the administration, no audit or connection-log entry and no {@code toString}; the
 * person's own page shows the account name and never the secret.
 */
@OpaaIntegrationTest
class ConnectedAccountLeakIntegrationTest {

  private static final String SECRET = PersonProbeSourceConnector.ACCEPTED_PASSWORD;
  private static final String WRONG = "falsches-geheimnis-2163";
  private static final String LABEL = "kontoname-2163";
  private static final String ADMIN = "/api/v1/admin/connection-profiles";
  private static final String ME = "/api/v1/me/connected-accounts";

  private static final Map<String, Level> WATCHED =
      Map.of(
          org.slf4j.Logger.ROOT_LOGGER_NAME,
          Level.INFO,
          "io.opaa.connection",
          Level.TRACE,
          "io.opaa.audit",
          Level.TRACE,
          "io.opaa.api",
          Level.TRACE);

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;

  private final ch.qos.logback.core.read.ListAppender<ILoggingEvent> appender =
      new ch.qos.logback.core.read.ListAppender<>();
  private final Map<Logger, Level> previousLevels = new LinkedHashMap<>();
  private final List<String> ownAnswers = new ArrayList<>();
  private final List<String> adminAnswers = new ArrayList<>();
  private UUID profile;
  private Instant start;

  @BeforeEach
  void attach() {
    start = Instant.now();
    appender.list = new CopyOnWriteArrayList<>();
    appender.start();
    WATCHED.forEach(
        (name, level) -> {
          Logger logger = (Logger) LoggerFactory.getLogger(name);
          previousLevels.put(logger, logger.getLevel());
          if (!name.equals(org.slf4j.Logger.ROOT_LOGGER_NAME)) {
            logger.setLevel(level);
          }
          logger.addAppender(appender);
        });
  }

  @AfterEach
  void detach() {
    previousLevels.forEach(
        (logger, level) -> {
          logger.detachAppender(appender);
          logger.setLevel(level);
        });
    if (profile != null) {
      jdbc.update("DELETE FROM connected_accounts WHERE profile_id = ?", profile);
      jdbc.update("DELETE FROM connection_log WHERE profile_id = ?", profile);
      ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", profile.toString());
      jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
    }
  }

  @Test
  void theSecretAndTheAccountNameAppearNowhereButTheNameOnTheOwnPage() throws Exception {
    String created =
        call(
            "dev-admin",
            post(ADMIN),
            """
            {"name": "Zugang Leck %s", "sourceType": "PERSON_PROBE", "serverUrl":
             "https://person.example.org", "authMethod": "PERSONAL_SECRET", "ownership": "PERSON"}
            """
                .formatted(UUID.randomUUID()),
            adminAnswers);
    profile = UUID.fromString(JsonPath.read(created, "$.id"));
    ConnectorReleases.releaseToAllAccounts(jdbc, "PROFILE:" + profile);
    String credentials = "{\"username\": \"" + LABEL + "\", \"secret\": \"%s\"}";

    call("dev-user", put(ME + "/" + profile), credentials.formatted(WRONG), ownAnswers);
    call("dev-user", put(ME + "/" + profile), credentials.formatted(SECRET), ownAnswers);
    String page = call("dev-user", get(ME), null, ownAnswers);
    call("dev-admin", get(ADMIN), null, adminAnswers);
    call("dev-admin", get(ADMIN + "/" + profile), null, adminAnswers);
    call("dev-admin", get(ADMIN + "/" + profile + "/impact"), null, adminAnswers);
    call("dev-admin", post(ADMIN + "/" + profile + "/disconnect-all"), null, adminAnswers);
    call("dev-user", put(ME + "/" + profile), credentials.formatted(SECRET), ownAnswers);
    call("dev-user", delete(ME + "/" + profile), null, ownAnswers);

    assertThat(page).contains(LABEL);
    assertThat(ownAnswers).allSatisfy(answer -> assertThat(answer).doesNotContain(SECRET, WRONG));
    assertThat(adminAnswers)
        .hasSize(5)
        .allSatisfy(answer -> assertThat(answer).doesNotContain(SECRET, WRONG, LABEL));
    List<String> audit =
        jdbc.queryForList(
            "SELECT coalesce(before, '') || coalesce(after, '') || coalesce(object_label, '')"
                + " FROM audit_log WHERE recorded_at >= ?",
            String.class,
            Timestamp.from(start));
    assertThat(audit)
        .isNotEmpty()
        .allSatisfy(entry -> assertThat(entry).doesNotContain(SECRET, WRONG, LABEL));
    List<String> log =
        jdbc.queryForList(
            "SELECT concat_ws('|', event_type, actor_ref, person_ref, account_label, profile_name,"
                + " cause) FROM connection_log WHERE profile_id = ?",
            String.class,
            profile);
    assertThat(log)
        .hasSize(4)
        .allSatisfy(entry -> assertThat(entry).doesNotContain(SECRET, WRONG, LABEL));
    assertThat(appender.list)
        .isNotEmpty()
        .allSatisfy(
            event -> {
              String throwable =
                  event.getThrowableProxy() == null
                      ? ""
                      : ThrowableProxyUtil.asString(event.getThrowableProxy());
              assertThat(event.getFormattedMessage() + throwable)
                  .doesNotContain(SECRET, WRONG, LABEL);
            });
  }

  @Test
  void noValueShowsInAToString() {
    ConnectedAccount account =
        new ConnectedAccount(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now());
    account.connected("enc:v1:" + LABEL, Instant.now(), false);
    AccountOverview.Account view =
        new AccountOverview.Account(
            UUID.randomUUID(),
            "Zugang",
            null,
            null,
            account.getState(),
            LABEL,
            true,
            true,
            null,
            null,
            Instant.now(),
            null,
            List.of());

    assertThat(account.toString()).doesNotContain(LABEL);
    assertThat(view.toString()).doesNotContain(LABEL);
    assertThat(NewSecret.personal(SECRET).toString()).doesNotContain(SECRET);
  }

  private String call(
      String user, MockHttpServletRequestBuilder request, String body, List<String> answers)
      throws Exception {
    request.header(DevAuthFilter.DEV_USER_HEADER, user);
    if (body != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    String answer =
        mockMvc
            .perform(request)
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    answers.add(answer);
    return answer;
  }
}
