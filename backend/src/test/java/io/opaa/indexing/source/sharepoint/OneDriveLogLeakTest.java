package io.opaa.indexing.source.sharepoint;

import static io.opaa.indexing.source.sharepoint.OneDriveTestDrives.DRIVE_ANNA;
import static io.opaa.indexing.source.sharepoint.OneDriveTestDrives.DRIVE_BERND;
import static io.opaa.indexing.source.sharepoint.OneDriveTestDrives.TOKEN_ANNA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.indexing.filesync.FileSyncHarness;
import io.opaa.indexing.job.IndexingRunEvent;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.RequestBudget;
import io.opaa.indexing.spi.SourceBrowser;
import io.opaa.indexing.spi.SourceCredentialsException;
import io.opaa.indexing.spi.SourceSettings;
import io.opaa.indexing.spi.SourceSyncStateRepository;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import io.opaa.msgraph.FakeGraphServer;
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
 * Neither the access token of the person's account nor the short-lived credential of a pre-signed
 * download address appears in a log line of the connector, the file sync, the Graph client or the
 * source-access primitives at TRACE, nor in a protocol entry - across throttling, a refused and a
 * broken download, a refused token, another person's OneDrive and every call outside a run. The log
 * names neither the owner (address, name) nor her files and folders.
 */
class OneDriveLogLeakTest {

  private static final List<String> WATCHED =
      List.of(
          "io.opaa.indexing",
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
    OneDriveTestDrives.arrange(server);
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
    server.failNext("/blob/bericht", 403, null, null, 1);
    server.failNext("/blob/notiz", 500, null, null, 1);
    events.addAll(harness.fullSync(store()).events());
    OneDriveSourceConnector connector =
        new OneDriveSourceConnector(
            server.origin(),
            OneDriveSourceConnector.AUTHORIZATION_TEMPLATE,
            SharePointSourceConnector.TOKEN_TEMPLATE,
            SharePointTestStores.connections(server),
            mock(SourceSyncStateRepository.class));
    connector.connectedAccount(settings(null));
    connector.browse(new SourceBrowser.Query(settings(ConnectorData.of(Map.of())), null));
    connector.testConnection(settings(OneDriveTestDrives.drive(DRIVE_BERND)), null);
    connector.openOriginal(
        new Document(
            "Bericht.txt",
            DriveRule.ONEDRIVE.filePath(DRIVE_ANNA, "bericht"),
            "text/plain",
            1L,
            SourceType.of("ONEDRIVE")),
        mock(KnowledgeLibrary.class),
        settings(OneDriveTestDrives.drive(DRIVE_ANNA)));
    server.acceptOnly("eyJ0eXAiOiJKV1QifQ.ein-anderes");
    events.addAll(harness.fullSync(store()).events());
    connector.testConnection(settings(OneDriveTestDrives.drive(DRIVE_ANNA)), null);
    try {
      connector.connectedAccount(settings(null));
    } catch (SourceCredentialsException expected) {
      // a refused token names no account; only what reaches the log matters here
    }

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
          .doesNotContain(TOKEN_ANNA)
          .doesNotContain("Bearer")
          .doesNotContain(server.presignedSecret())
          .doesNotContain("tempauth");
    }
    // the protocol shows file names to the owner; the log names neither her nor her files
    assertThat(logs)
        .doesNotContain("anna@contoso.example")
        .doesNotContain("Anna Amsel")
        .doesNotContain("Bericht.txt")
        .doesNotContain("Notiz.txt")
        .doesNotContain("Akten");
  }

  private SourceSettings settings(ConnectorData data) {
    return new SourceSettings(null, server.origin().toString(), TOKEN_ANNA, data);
  }

  private DriveFileStore store() {
    SourceSettings source = new SourceSettings(null, server.origin().toString(), null, null);
    RequestBudget budget = RequestBudget.unbounded();
    return new DriveFileStore(
        DriveRule.ONEDRIVE,
        SharePointTestStores.connections(server).open(source, () -> TOKEN_ANNA, budget),
        budget.meter(),
        List.of(SharePointLibrary.fromJson(Map.of("driveId", DRIVE_ANNA))),
        2);
  }
}
