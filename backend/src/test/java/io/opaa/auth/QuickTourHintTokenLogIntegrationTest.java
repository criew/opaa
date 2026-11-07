package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The marker of the quick-tour hint set through the token path of the {@code oidc} mode - bearer
 * token, JWT decoder, provisioning - with every logger at DEBUG: no line names the account by id,
 * subject, issuer, address or display name, and no line of OPAA's own loggers mentions the call.
 * Spring's own DEBUG lines (security, web, jdbc) name every call, this one included, by path,
 * client address, handler or SQL; the operations handbook names that level a diagnostic only.
 */
@OpaaLocalAuthMockMvcTest
class QuickTourHintTokenLogIntegrationTest {

  private static final String HINT = "/api/v1/me/quick-tour-hint";

  @Autowired private MockMvc mockMvc;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private QuickTourHint quickTourHint;

  private LocalAccountFixtures fixtures;
  private LocalAccount person;

  @BeforeEach
  void setUp() {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    fixtures.localProvider(true);
    person = fixtures.activeUser("rundgang-" + UUID.randomUUID() + "@stadt.example");
  }

  @AfterEach
  void tearDown() {
    fixtures.cleanUp();
  }

  @Test
  void settingTheMarkerWithATokenLeavesNoLogLineAboutThePerson() throws Exception {
    String bearer = bearer(login());
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    Level previousLevel = root.getLevel();
    root.setLevel(Level.DEBUG);
    logs.start();
    root.addAppender(logs);
    try {
      mockMvc
          .perform(put(HINT).header(HttpHeaders.AUTHORIZATION, bearer))
          .andExpect(status().isNoContent());
    } finally {
      root.detachAppender(logs);
      root.setLevel(previousLevel);
    }

    assertThat(quickTourHint.isDone(person.id())).isTrue();
    User user = person.user();
    List<String> identifiers =
        Stream.of(
                user.getId().toString(),
                user.getSubject(),
                user.getIssuer(),
                user.getEmail(),
                user.getDisplayName())
            .filter(Objects::nonNull)
            .filter(value -> !value.isBlank())
            .toList();
    for (ILoggingEvent event : logs.list) {
      String line = event.getFormattedMessage();
      for (String identifier : identifiers) {
        assertThat(line).as("log line of %s", event.getLoggerName()).doesNotContain(identifier);
      }
      if (event.getLoggerName().startsWith("io.opaa")) {
        assertThat(line).as("log line of %s", event.getLoggerName()).doesNotContain(HINT);
      }
    }
  }

  private MvcResult login() throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/auth/local/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\""
                        + person.email()
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
