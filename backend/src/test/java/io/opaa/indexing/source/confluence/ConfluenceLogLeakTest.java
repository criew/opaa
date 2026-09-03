package io.opaa.indexing.source.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.api.types.ConfluenceEdition;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Credentials appear in no log line of the access layer or the shared source-access primitives -
 * asserted on the rendered log output, including throwable text, across the failure paths that log
 * at all (rate limiting, refused credentials, redirect handling).
 */
class ConfluenceLogLeakTest {

  private static final String EMAIL = "dienst@behoerde.example";
  private static final String TOKEN = "hochgeheimes-token-4711";

  private static final String[] WATCHED_LOGGERS = {
    "io.opaa.indexing.source.confluence", "io.opaa.sourceaccess"
  };

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private final java.util.Map<Logger, Level> previousLevels = new java.util.LinkedHashMap<>();
  private FakeConfluenceServer server;

  @BeforeEach
  void attach() {
    appender.start();
    for (String name : WATCHED_LOGGERS) {
      Logger logger = (Logger) LoggerFactory.getLogger(name);
      previousLevels.put(logger, logger.getLevel());
      logger.setLevel(Level.TRACE);
      logger.addAppender(appender);
    }
  }

  @AfterEach
  void detach() {
    previousLevels.forEach(
        (logger, level) -> {
          logger.detachAppender(appender);
          logger.setLevel(level);
        });
    if (server != null) {
      server.close();
    }
  }

  @Test
  void logsCarryNeitherTokenNorEmail() throws Exception {
    for (ConfluenceEdition edition : ConfluenceEdition.values()) {
      server = new FakeConfluenceServer(edition);
      server.addSpace("1", "ENG", "Engineering");
      ConfluenceCredentials good = server.addToken(EMAIL, TOKEN, Set.of("ENG"));
      ConfluenceClientFactory factory =
          new ConfluenceClientFactory(
              new ConfluenceProperties(2, null, null, 2, null, 0, 0, null, 0),
              TargetAddressValidator.disabled(),
              duration -> {});
      ConfluenceClient client =
          factory.create(
              new ConfluenceConnection(
                  URI.create(server.baseUrl()), edition, good, null, -1, false));

      server.throttleNext(1, "1");
      client.listSpaces();
      server.throttleNext(5, "1");
      assertThatThrownBy(client::listSpaces).isInstanceOf(ConfluenceAccessException.class);

      ConfluenceCredentials wrong =
          edition == ConfluenceEdition.CLOUD
              ? new ConfluenceCredentials.CloudApiToken(EMAIL, TOKEN + "-falsch")
              : new ConfluenceCredentials.DataCenterPersonalAccessToken(TOKEN + "-falsch");
      ConfluenceClient wrongClient =
          factory.create(
              new ConfluenceConnection(
                  URI.create(server.baseUrl()), edition, wrong, null, -1, false));
      assertThatThrownBy(wrongClient::verifyCredentials)
          .isInstanceOf(ConfluenceAccessException.class);
      server.close();
    }

    List<String> rendered =
        appender.list.stream()
            .map(
                event ->
                    event.getFormattedMessage()
                        + (event.getThrowableProxy() == null
                            ? ""
                            : " " + event.getThrowableProxy().getMessage()))
            .toList();
    assertThat(rendered).as("something was logged at all").isNotEmpty();
    assertThat(rendered)
        .noneSatisfy(
            line -> {
              assertThat(line).contains(TOKEN);
            })
        .noneSatisfy(line -> assertThat(line).contains(EMAIL))
        .noneSatisfy(line -> assertThat(line).contains("Basic "))
        .noneSatisfy(line -> assertThat(line).contains("Bearer "));
  }
}
