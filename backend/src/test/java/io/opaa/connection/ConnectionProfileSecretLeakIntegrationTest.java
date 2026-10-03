package io.opaa.connection;

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
import io.opaa.test.OpaaIntegrationTest;
import java.nio.charset.StandardCharsets;
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
 * The client secret of a profile appears in no answer, no log line and no audit entry, and is
 * stored only as ciphertext - across create, read, list, update, a refused update and delete
 * (acceptance criterion of #2160, pattern of {@code S3LogLeakTest}).
 */
@OpaaIntegrationTest
class ConnectionProfileSecretLeakIntegrationTest {

  private static final String SECRET = "hochgeheimes-client-secret-2160";
  private static final String NEW_SECRET = "noch-geheimeres-client-secret-2160";
  private static final String ADMIN = "/api/v1/admin/connection-profiles";

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
  private final List<String> answers = new ArrayList<>();
  private UUID profile;

  @BeforeEach
  void attach() {
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
      jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
    }
  }

  @Test
  void theClientSecretAppearsInNoAnswerLogLineOrAuditEntry() throws Exception {
    String name = "Zugang Leck " + UUID.randomUUID();
    String created =
        call(
            post(ADMIN),
            """
            {"name": "%s", "sourceType": "PROFILE_PROBE", "serverUrl": "https://probe.example.org",
             "authMethod": "OAUTH", "ownership": "LIBRARY", "clientId": "opaa",
             "clientSecret": "%s"}
            """
                .formatted(name, SECRET));
    profile = UUID.fromString(JsonPath.read(created, "$.id"));
    assertThat((Boolean) JsonPath.read(created, "$.clientSecretSet")).isTrue();
    String stored =
        jdbc.queryForObject(
            "SELECT client_secret_ciphertext FROM connection_profiles WHERE id = ?",
            String.class,
            profile);
    assertThat(stored).startsWith("enc:v1:").doesNotContain(SECRET);

    call(get(ADMIN), null);
    call(get(ADMIN + "/" + profile), null);
    String update =
        """
        {"name": "%s", "serverUrl": "https://probe.example.org", "authMethod": "OAUTH",
         "ownership": "LIBRARY", "clientId": "opaa", "clientSecret": "%s"}
        """;
    call(put(ADMIN + "/" + profile), update.formatted(name, NEW_SECRET));
    // a refused change carrying a secret must not echo it either
    call(put(ADMIN + "/" + profile), update.formatted("", SECRET));
    call(delete(ADMIN + "/" + profile), null);

    assertThat(answers)
        .isNotEmpty()
        .allSatisfy(answer -> assertThat(answer).doesNotContain(SECRET, NEW_SECRET));
    List<String> audit =
        jdbc.queryForList(
            "SELECT coalesce(before, '') || coalesce(after, '') || coalesce(object_label, '')"
                + " FROM audit_log WHERE object_id = ?",
            String.class,
            profile.toString());
    assertThat(audit)
        .hasSize(3)
        .allSatisfy(entry -> assertThat(entry).doesNotContain(SECRET, NEW_SECRET));
    assertThat(appender.list)
        .allSatisfy(
            event -> {
              String throwable =
                  event.getThrowableProxy() == null
                      ? ""
                      : ThrowableProxyUtil.asString(event.getThrowableProxy());
              assertThat(event.getFormattedMessage() + throwable)
                  .doesNotContain(SECRET, NEW_SECRET);
            });
  }

  private String call(MockHttpServletRequestBuilder request, String body) throws Exception {
    request.header(DevAuthFilter.DEV_USER_HEADER, "dev-admin");
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
