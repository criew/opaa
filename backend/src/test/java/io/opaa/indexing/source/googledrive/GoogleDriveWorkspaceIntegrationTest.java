package io.opaa.indexing.source.googledrive;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.indexing.filesync.FileSyncHarness;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingRunEvent;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.RequestBudget;
import io.opaa.indexing.source.ServiceAccountKey;
import io.opaa.indexing.source.ServiceAccountKeyAuth;
import io.opaa.indexing.source.ServiceAccountTokens;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.security.TargetAddressValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;

/**
 * Stage 3 against a real Google Workspace, read-only (ADR-0040, "unsicher" findings): runs only
 * through {@code ./gradlew googleDriveIntegrationTest} on the maintainer's machine, with the key
 * file outside the repository. Setup and test data: {@code README.md} beside this class. A Google
 * outage shows as a failed sign-in, not as a finding about OPAA.
 */
@EnabledIfEnvironmentVariable(named = "OPAA_GDRIVE_IT_KEY_FILE", matches = ".+")
class GoogleDriveWorkspaceIntegrationTest {

  private static final String KEY_FILE = System.getenv("OPAA_GDRIVE_IT_KEY_FILE");
  private static final String FOLDER_ID = System.getenv("OPAA_GDRIVE_IT_FOLDER_ID");
  private static final String DRIVE_ID = System.getenv("OPAA_GDRIVE_IT_DRIVE_ID");
  private static final String SUBJECT = System.getenv("OPAA_GDRIVE_IT_SUBJECT");

  private static final List<String> WATCHED =
      List.of("io.opaa.indexing", "io.opaa.sourceaccess", "io.opaa.security");

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private String key;
  private String token;

  @BeforeEach
  void signIn() throws Exception {
    Path keyFile = Path.of(KEY_FILE).toAbsolutePath().normalize();
    Path repository = Path.of("").toAbsolutePath().getParent();
    assertThat(keyFile.startsWith(repository))
        .as("the key file lies outside the repository")
        .isFalse();
    appender.list = new CopyOnWriteArrayList<>();
    appender.start();
    for (String name : WATCHED) {
      Logger logger = (Logger) LoggerFactory.getLogger(name);
      logger.setLevel(Level.DEBUG);
      logger.addAppender(appender);
    }
    key = ServiceAccountKey.parse(Files.readString(keyFile)).storedForm();
    token =
        new ServiceAccountTokens(new TargetAddressValidator(true, List.of()), Clock.systemUTC())
            .accessToken(
                key,
                SUBJECT == null || SUBJECT.isBlank() ? null : SUBJECT,
                new ServiceAccountKeyAuth(
                    GoogleDriveSourceConnector.TOKEN_ENDPOINT, GoogleDriveSourceConnector.SCOPE),
                null);
  }

  @AfterEach
  void checkLogsAndDetach() {
    String logs =
        appender.list.stream()
            .map(
                event ->
                    event.getFormattedMessage()
                        + (event.getThrowableProxy() == null
                            ? ""
                            : event.getThrowableProxy().getMessage()))
            .collect(Collectors.joining("\n"));
    for (String name : WATCHED) {
      Logger logger = (Logger) LoggerFactory.getLogger(name);
      logger.detachAppender(appender);
      logger.setLevel(null);
    }
    if (key != null) {
      String body = key.substring(key.indexOf("PRIVATE KEY-----") + 20);
      assertThat(logs).doesNotContain(body.substring(0, 40));
    }
    if (token != null) {
      assertThat(logs).doesNotContain(token);
    }
  }

  @Test
  void theConnectionTestReachesEveryScope() {
    SourceConnectionTestResult result = connector().testConnection(settings(), null);

    assertThat(result.reachable()).as(result.message()).isTrue();
  }

  @Test
  void aFullSyncReadsTheTestDataAsTheReadmeDescribesIt() throws Exception {
    FileSyncHarness harness = new FileSyncHarness();

    FileSyncHarness.Run run = harness.fullSync(store());

    assertThat(run.failure()).isNull();
    assertThat(run.listingComplete()).isTrue();
    assertThat(run.eventsOf(IndexingEventCategory.FORMAT_MISMATCH))
        .as("the Doc over 10 MB is exported as text: Drive names exportSizeLimitExceeded")
        .extracting(IndexingRunEvent::getMessage)
        .contains(DriveFileStore.EXPORTED_AS_TEXT);
    assertThat(run.eventsOf(IndexingEventCategory.UNSUPPORTED_FORMAT))
        .extracting(IndexingRunEvent::getMessage)
        .anyMatch(message -> message.endsWith(DriveFileStore.SHORTCUTS_NOTE));
    List<String> names = new ArrayList<>();
    for (String path : harness.storedPaths()) {
      names.add(harness.stored(path).orElseThrow().getFileName());
    }
    assertThat(names).contains("Protokoll.docx", "Zahlen.xlsx", "Notiz.txt", "ohne-endung");
  }

  @Test
  void aChangeRunAfterTheFullSyncReadsTheStreamsCleanly() throws Exception {
    FileSyncHarness harness = new FileSyncHarness();
    harness.fullSync(store());

    FileSyncHarness.Run run = harness.changeRun(store());

    assertThat(run.failure()).isNull();
    assertThat(
            harness
                .state()
                .canReadChanges(
                    GoogleDriveIndexingExecutor.streams(driveSettings()),
                    Duration.ofDays(7),
                    FileSyncHarness.NOW))
        .isTrue();
  }

  private GoogleDriveSourceConnector connector() {
    return new GoogleDriveSourceConnector(
        GoogleDriveSourceConnector.API_BASE,
        GoogleDriveSourceConnector.TOKEN_ENDPOINT,
        apis(),
        null);
  }

  private DriveFileStore store() {
    return new DriveFileStore(
        apis().open(settings(), () -> token, RequestBudget.unbounded()),
        driveSettings(),
        GoogleDriveProperties.MAX_PAGE_SIZE);
  }

  private DriveApiFactory apis() {
    return new DriveApiFactory(
        GoogleDriveProperties.defaults(),
        new TargetAddressValidator(true, List.of()),
        io.opaa.sourceaccess.Sleeper.threadSleep());
  }

  private SourceSettings settings() {
    return new SourceSettings(
        null,
        GoogleDriveSourceConnector.API_BASE.toString(),
        null,
        token,
        false,
        driveSettings().toData());
  }

  private static GoogleDriveSettings driveSettings() {
    List<Map<String, Object>> scopes = new ArrayList<>();
    if (FOLDER_ID != null && !FOLDER_ID.isBlank()) {
      scopes.add(Map.of("folder", FOLDER_ID));
    }
    if (DRIVE_ID != null && !DRIVE_ID.isBlank()) {
      scopes.add(Map.of("drive", DRIVE_ID));
    }
    Map<String, Object> settings = new LinkedHashMap<>();
    settings.put("scopes", scopes);
    if (SUBJECT != null && !SUBJECT.isBlank()) {
      settings.put("subject", SUBJECT);
    }
    return GoogleDriveSettings.read(ConnectorData.of(settings));
  }
}
