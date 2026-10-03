package io.opaa.indexing.source.googledrive;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.indexing.filesync.FileSyncHarness;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.RequestBudget;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.security.TargetAddressValidator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The access token appears in no log line of the connector, the file sync, the run frame or the
 * source-access primitives at TRACE - asserted on the rendered output including throwable text,
 * across the paths that log at all: throttling, a refused file, a refused token, a daily limit. The
 * key itself never reaches the connector; {@code ServiceAccountTokensTest} covers the core.
 */
class GoogleDriveLogLeakTest {

  private static final List<String> WATCHED =
      List.of(
          "io.opaa.indexing.source",
          "io.opaa.indexing.filesync",
          "io.opaa.sourceaccess",
          "io.opaa.security.TargetAddressValidator");

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private FakeDriveServer server;

  @BeforeEach
  void attach() {
    appender.list = new CopyOnWriteArrayList<>();
    appender.start();
    for (String name : WATCHED) {
      Logger logger = (Logger) LoggerFactory.getLogger(name);
      logger.setLevel(Level.TRACE);
      logger.addAppender(appender);
    }
    server = new FakeDriveServer();
    server.addDrive("drive0", "Ablage");
    server.file("a", "A.txt", "text/plain", "drive0", "drive0", "Text.");
    server.file("b", "B.txt", "text/plain", "drive0", "drive0", "Text.");
  }

  @AfterEach
  void detach() {
    for (String name : WATCHED) {
      Logger logger = (Logger) LoggerFactory.getLogger(name);
      logger.detachAppender(appender);
      logger.setLevel(null);
    }
    server.close();
  }

  @Test
  void noLogLineCarriesTheAccessToken() throws Exception {
    FileSyncHarness harness = new FileSyncHarness();
    server.failNext("files", 429, "rateLimitExceeded", 1);
    server.failNext("files/b", 403, "forbidden", 1);
    harness.fullSync(store());
    server.failNext("files", 403, "dailyLimitExceeded", 5);
    harness.fullSync(store());
    server.rejectToken();
    harness.fullSync(store());

    String logs =
        appender.list.stream()
            .map(
                event ->
                    event.getFormattedMessage()
                        + (event.getThrowableProxy() == null
                            ? ""
                            : event.getThrowableProxy().getMessage()))
            .collect(Collectors.joining("\n"));
    assertThat(appender.list).isNotEmpty();
    assertThat(logs).doesNotContain(FakeDriveServer.TOKEN).doesNotContain("Bearer");
  }

  private DriveFileStore store() {
    DriveApiFactory apis =
        new DriveApiFactory(
            GoogleDriveProperties.defaults(), TargetAddressValidator.disabled(), wait -> {});
    return new DriveFileStore(
        apis.open(
            new SourceSettings(null, server.base().toString(), null, null, false, null),
            () -> FakeDriveServer.TOKEN,
            RequestBudget.unbounded()),
        GoogleDriveSettings.read(
            ConnectorData.of(Map.of("scopes", List.of(Map.of("drive", "drive0"))))),
        1);
  }
}
