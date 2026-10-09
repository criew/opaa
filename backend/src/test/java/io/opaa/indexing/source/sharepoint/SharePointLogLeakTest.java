package io.opaa.indexing.source.sharepoint;

import static io.opaa.indexing.source.sharepoint.SharePointTestStores.DRIVE_0;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.indexing.filesync.FileSyncHarness;
import io.opaa.indexing.job.IndexingRunEvent;
import io.opaa.indexing.source.RequestBudget;
import io.opaa.msgraph.FakeGraphServer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Neither the access token nor the short-lived credential of a pre-signed download address appears
 * in a log line of the connector, the file sync, the Graph client or the source-access primitives
 * at TRACE, nor in a protocol entry - across throttling, a refused download, a broken transfer and
 * a refused token. The client secret never reaches the connector; the sign-in tests cover the core.
 */
class SharePointLogLeakTest {

  private static final List<String> WATCHED =
      List.of(
          "io.opaa.indexing.source",
          "io.opaa.indexing.filesync",
          "io.opaa.msgraph",
          "io.opaa.sourceaccess",
          "io.opaa.security.TargetAddressValidator");

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private FakeGraphServer server;

  @BeforeEach
  void attach() {
    appender.list = new CopyOnWriteArrayList<>();
    appender.start();
    for (String name : WATCHED) {
      Logger logger = (Logger) LoggerFactory.getLogger(name);
      logger.setLevel(Level.TRACE);
      logger.addAppender(appender);
    }
    server = new FakeGraphServer();
    SharePointTestStores.twoLibraries(server);
    String root = FakeGraphServer.rootId(DRIVE_0);
    server.file(DRIVE_0, "a", "A.txt", root, "Text A.".getBytes(StandardCharsets.UTF_8));
    server.file(DRIVE_0, "b", "B.txt", root, "Text B.".getBytes(StandardCharsets.UTF_8));
    server.file(DRIVE_0, "c", "C.txt", root, "Text C.".getBytes(StandardCharsets.UTF_8));
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
  void noLogLineAndNoProtocolEntryCarriesTheTokenOrTheDownloadAddress() throws Exception {
    FileSyncHarness harness = new FileSyncHarness();
    List<IndexingRunEvent> events = new ArrayList<>();
    server.failNext("root/delta", 429, "TooManyRequests", "1", 1);
    server.failNext("/blob/b", 403, null, null, 1);
    server.failNext("/blob/c", 500, null, null, 1);
    events.addAll(harness.fullSync(store()).events());
    server.acceptOnly("eyJ0eXAiOiJKV1QifQ.ein-anderes");
    events.addAll(harness.fullSync(store()).events());

    String logs =
        appender.list.stream()
            .map(
                event ->
                    event.getFormattedMessage()
                        + (event.getThrowableProxy() == null
                            ? ""
                            : event.getThrowableProxy().getMessage()))
            .collect(Collectors.joining("\n"));
    String protocol =
        events.stream().map(IndexingRunEvent::getMessage).collect(Collectors.joining("\n"));
    assertThat(appender.list).isNotEmpty();
    assertThat(server.downloadAuthorizations()).isNotEmpty();
    for (String text : List.of(logs, protocol)) {
      assertThat(text)
          .doesNotContain(FakeGraphServer.TOKEN)
          .doesNotContain("Bearer")
          .doesNotContain(server.presignedSecret())
          .doesNotContain("tempauth");
    }
  }

  private SharePointFileStore store() {
    return SharePointTestStores.store(
        server,
        SharePointTestStores.settings(
            java.util.Map.of("libraries", List.of(java.util.Map.of("driveId", DRIVE_0)))),
        2,
        RequestBudget.unbounded());
  }
}
