package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Credentials appear in no log line of the access layer, the shared source-access primitives, the
 * SDK or the HTTP client at DEBUG, the level an operator turns on to diagnose (the SDK's request
 * log lists header names, never values; the signer and the HTTP client's header/wire logs would
 * print the session token and are pinned to INFO in application.yml) - asserted on the rendered log
 * output, including throwable text, across the failure paths that log at all (throttling, refused
 * credentials, a missing object, a refused download, an unreachable endpoint).
 */
class S3LogLeakTest {

  private static final String ACCESS_KEY = "AKIALEAKTESTKEY";
  private static final String SECRET_KEY = "hochgeheimer-secret-key-4711";
  private static final String SESSION_TOKEN = "hochgeheimes-session-token-0815";

  /**
   * Loggers that print a credential at DEBUG - the SDK's SigV4 signer (canonical request with the
   * session token) and the HTTP client's header and wire logs - and that application.yml therefore
   * pins to INFO. The pins are what this test relies on, so it checks they are configured.
   */
  private static final List<String> PINNED_LOGGERS =
      List.of(
          "software.amazon.awssdk.http.auth.aws.internal.signer",
          "org.apache.hc.client5.http.headers",
          "org.apache.hc.client5.http.wire");

  private static final Map<String, Level> WATCHED =
      Map.of(
          "io.opaa.indexing.source.s3",
          Level.TRACE,
          "io.opaa.sourceaccess",
          Level.TRACE,
          "software.amazon.awssdk",
          Level.DEBUG,
          "org.apache.hc.client5",
          Level.DEBUG,
          PINNED_LOGGERS.get(0),
          Level.INFO,
          PINNED_LOGGERS.get(1),
          Level.INFO,
          PINNED_LOGGERS.get(2),
          Level.INFO);

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private final Map<Logger, Level> previousLevels = new LinkedHashMap<>();
  private StubS3Server server;

  @BeforeEach
  void attach() throws Exception {
    // the HTTP client logs from its own threads too
    appender.list = new CopyOnWriteArrayList<>();
    appender.start();
    WATCHED.forEach(
        (name, level) -> {
          Logger logger = (Logger) LoggerFactory.getLogger(name);
          previousLevels.put(logger, logger.getLevel());
          logger.setLevel(level);
          logger.addAppender(appender);
        });
    server = new StubS3Server();
    server.addBucket("docs");
    server.putObject("docs", "a.pdf", new byte[10], "application/pdf");
    server.putObject("docs", "gross.bin", new byte[3000], "application/octet-stream");
  }

  @AfterEach
  void detach() {
    previousLevels.forEach(
        (logger, level) -> {
          logger.detachAppender(appender);
          logger.setLevel(level);
        });
    server.close();
  }

  @Test
  void thePinsThisTestReliesOnArePartOfTheApplicationConfiguration() throws Exception {
    try (InputStream yml = getClass().getResourceAsStream("/application.yml")) {
      assertThat(yml).isNotNull();
      String text = new String(yml.readAllBytes(), StandardCharsets.UTF_8);
      for (String logger : PINNED_LOGGERS) {
        assertThat(text).contains("    " + logger + ": INFO\n");
      }
    }
  }

  @Test
  void logsCarryNeitherSecretKeyNorSessionTokenNorAccessKey() throws Exception {
    S3Properties properties =
        new S3Properties(1000, 0, Duration.ofSeconds(3), 2, Duration.ofMillis(1), 0, null, 0, 0);
    S3ClientFactory factory = new S3ClientFactory(properties, TargetAddressValidator.disabled());
    S3Connection connection =
        new S3Connection(
            URI.create(server.endpoint()),
            "us-east-1",
            true,
            new S3Credentials(ACCESS_KEY, SECRET_KEY, SESSION_TOKEN),
            null,
            -1,
            false);

    try (S3ObjectStore store = factory.create(connection, List.of(S3Scope.of("docs", "")))) {
      store.listObjects(S3Scope.of("docs", ""), null);
      server.failNext(503, "SlowDown", 1);
      store.listObjects(S3Scope.of("docs", ""), null);
      server.failNext(503, "SlowDown", 5);
      assertThatThrownBy(() -> store.listObjects(S3Scope.of("docs", ""), null))
          .isInstanceOf(S3AccessException.RateLimited.class);
      server.failNext(403, "InvalidAccessKeyId", 1);
      assertThatThrownBy(() -> store.listObjects(S3Scope.of("docs", ""), null))
          .isInstanceOf(S3AccessException.Authentication.class);
      assertThatThrownBy(() -> store.headObject("docs", "fehlt.pdf"))
          .isInstanceOf(S3AccessException.ObjectNotFound.class);
      assertThatThrownBy(() -> store.getObject("docs", "gross.bin", 1024))
          .isInstanceOf(S3AccessException.ObjectTooLarge.class);
      server.close();
      assertThatThrownBy(() -> store.headObject("docs", "a.pdf"))
          .isInstanceOf(S3AccessException.Unreachable.class);
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
        .noneSatisfy(line -> assertThat(line).contains(SECRET_KEY))
        .noneSatisfy(line -> assertThat(line).contains(SESSION_TOKEN))
        .noneSatisfy(line -> assertThat(line).contains(ACCESS_KEY))
        .noneSatisfy(line -> assertThat(line).contains("Signature="));
  }
}
