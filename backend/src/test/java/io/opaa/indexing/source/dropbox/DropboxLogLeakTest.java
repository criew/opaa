package io.opaa.indexing.source.dropbox;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.indexing.filesync.FileSyncHarness;
import io.opaa.indexing.job.IndexingRunEvent;
import io.opaa.indexing.source.ConnectorData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Neither the access token nor a refused one appears in a log line of the connector, the file sync
 * or the source-access primitives at TRACE, nor in a protocol entry - across throttling, a refused
 * file ({@code 409}), a broken download, a refused token and a change run. The refresh token and
 * the app secret never reach the connector; the token store's tests cover the core.
 */
class DropboxLogLeakTest {

  private static final String REFUSED = "sl.abgewiesenes-token";

  private static final List<String> WATCHED =
      List.of(
          "io.opaa.indexing.source",
          "io.opaa.indexing.filesync",
          "io.opaa.sourceaccess",
          "io.opaa.security.TargetAddressValidator");

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private FakeDropboxServer server;

  @BeforeEach
  void attach() {
    appender.list = new CopyOnWriteArrayList<>();
    appender.start();
    for (String name : WATCHED) {
      Logger logger = (Logger) LoggerFactory.getLogger(name);
      logger.setLevel(Level.TRACE);
      logger.addAppender(appender);
    }
    server = new FakeDropboxServer();
    server.file("/Akten/a.txt", "Text A.");
    server.file("/Akten/b.txt", "Text B.");
    server.file("/Gesperrt/c.txt", "Text C.");
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
  void noLogLineAndNoProtocolEntryCarriesATokenOrABearerHeader() throws Exception {
    FileSyncHarness harness = new FileSyncHarness();
    List<IndexingRunEvent> events = new ArrayList<>();
    server.throttleNext(1);
    server.denyReading("/Gesperrt");
    server.breakNextDownload();
    events.addAll(harness.fullSync(store(FakeDropboxServer.TOKEN)).events());
    server.file("/Akten/neu.txt", "Neu.");
    server.changed("/Akten/neu.txt");
    events.addAll(harness.changeRun(store(FakeDropboxServer.TOKEN)).events());
    events.addAll(harness.fullSync(store(REFUSED)).events());

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
    assertThat(server.tokens()).contains(REFUSED);
    for (String text : List.of(logs, protocol)) {
      assertThat(text)
          .doesNotContain(FakeDropboxServer.TOKEN)
          .doesNotContain(REFUSED)
          .doesNotContain("Bearer");
    }
  }

  private DropboxFileStore store(String token) {
    return new DropboxFileStore(
        DropboxFileStoreContractTest.api(server, token),
        DropboxSettings.read(ConnectorData.of(Map.of("folders", List.of("/Akten", "/Gesperrt")))),
        2);
  }
}
