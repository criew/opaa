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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Credentials appear in no log line of the access layer, the shared source-access primitives or the
 * SDK at the levels an operator turns on to diagnose (the SDK's request log at DEBUG lists header
 * names, never values; its signer would print the session token and is pinned to INFO in
 * application.yml) - asserted on the rendered log output, including throwable text, across the
 * failure paths that log at all (throttling, refused credentials, a missing object, a refused
 * download, an unreachable endpoint).
 */
class S3LogLeakTest {

  private static final String ACCESS_KEY = "AKIALEAKTESTKEY";
  private static final String SECRET_KEY = "hochgeheimer-secret-key-4711";
  private static final String SESSION_TOKEN = "hochgeheimes-session-token-0815";

  /** The SDK's SigV4 signer prints the session token at DEBUG; application.yml pins it. */
  private static final String SIGNER_LOGGER =
      "software.amazon.awssdk.http.auth.aws.internal.signer";

  private static final Map<String, Level> WATCHED =
      Map.of(
          "io.opaa.indexing.source.s3",
          Level.TRACE,
          "io.opaa.sourceaccess",
          Level.TRACE,
          "software.amazon.awssdk",
          Level.DEBUG,
          SIGNER_LOGGER,
          Level.INFO,
          "org.apache.hc.client5",
          Level.INFO);

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private final Map<Logger, Level> previousLevels = new LinkedHashMap<>();
  private StubS3Server server;

  @BeforeEach
  void attach() throws Exception {
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
  void theSignerPinThisTestReliesOnIsPartOfTheApplicationConfiguration() throws Exception {
    try (InputStream yml = getClass().getResourceAsStream("/application.yml")) {
      assertThat(yml).isNotNull();
      String text = new String(yml.readAllBytes(), StandardCharsets.UTF_8);
      assertThat(text).contains("    " + SIGNER_LOGGER + ": INFO\n");
    }
  }

  @Test
  void logsCarryNeitherSecretKeyNorSessionTokenNorAccessKey() throws Exception {
    S3Properties properties =
        new S3Properties(1000, 0, Duration.ofSeconds(3), 2, Duration.ofMillis(1), 0);
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
