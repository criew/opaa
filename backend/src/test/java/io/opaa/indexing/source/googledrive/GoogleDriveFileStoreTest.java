package io.opaa.indexing.source.googledrive;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.filesync.FileSyncHarness;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingRunEvent;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.RequestBudget;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.security.TargetAddressValidator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the Drive store adds to the contract (ADR-0040, Entscheidungen 6 to 10), run through the
 * file sync against the {@link FakeDriveServer}: export of Google formats and its limit, shortcuts
 * and types without export, locked downloads, throttling and the daily limit, and the change log
 * with removals, moves out of scope, structure changes and expired cursors.
 */
class GoogleDriveFileStoreTest {

  private static final String DOCX =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
  private static final Set<String> STREAMS = Set.of("drive:drive0", "user");
  private static final Duration WEEK = Duration.ofDays(7);

  private FakeDriveServer server;
  private FileSyncHarness harness;

  @BeforeEach
  void setUp() throws Exception {
    server = new FakeDriveServer();
    server.addDrive("drive0", "Ablage");
    server.folder("prot", "Protokolle", "drive0", "drive0");
    server.file("bericht", "Bericht.txt", "text/plain", "prot", "drive0", "Ein Bericht.");
    server.folder("folder1", "Freigabe", FakeDriveServer.ROOT_ID, null);
    server.file("notiz", "Notiz.txt", "text/plain", "folder1", null, "Eine Notiz.");
    harness = new FileSyncHarness();
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  @Test
  void aGoogleDocIsExportedAsDocxUnderItsOfficeName() {
    server.file("doc", "Protokoll", "application/vnd.google-apps.document", "prot", "drive0", "x");

    FileSyncHarness.Run run = harness.fullSync(store());

    assertThat(run.failure()).isNull();
    assertThat(run.ingested()).contains(path("doc"));
    assertThat(harness.stored(path("doc")).orElseThrow().getFileName()).isEqualTo("Protokoll.docx");
    assertThat(harness.stored(path("doc")).orElseThrow().getLastModifiedRemote())
        .startsWith("g:")
        .endsWith("|docx");
    assertThat(server.requests()).contains("files/doc/export");
  }

  @Test
  void overTheExportLimitADocBecomesTextWithANote() {
    server.file("doc", "Protokoll", "application/vnd.google-apps.document", "prot", "drive0", "x")
            .exportTooLarge =
        true;

    FileSyncHarness.Run run = harness.fullSync(store());

    assertThat(run.failure()).isNull();
    assertThat(harness.stored(path("doc")).orElseThrow().getFileName()).isEqualTo("Protokoll.txt");
    assertThat(run.eventsOf(IndexingEventCategory.FORMAT_MISMATCH))
        .extracting(IndexingRunEvent::getMessage)
        .containsExactly(DriveFileStore.EXPORTED_AS_TEXT);
  }

  @Test
  void overTheExportLimitASheetIsSkippedAndTheRunGoesOn() {
    server.file("sheet", "Zahlen", "application/vnd.google-apps.spreadsheet", "prot", "drive0", "x")
            .exportTooLarge =
        true;

    FileSyncHarness.Run run = harness.fullSync(store());

    assertThat(run.failure()).isNull();
    assertThat(run.listingComplete()).isTrue();
    assertThat(run.eventsOf(IndexingEventCategory.REJECTED))
        .extracting(IndexingRunEvent::getMessage, IndexingRunEvent::getReference)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(DriveFileStore.SHEET_TOO_LARGE, path("sheet")));
    assertThat(run.ingested()).contains(path("bericht"), path("notiz"));
  }

  @Test
  void shortcutsAndGoogleTypesWithoutExportAreNoDocumentsAndNeverFetched() {
    server.file("kurz", "Verweis", DriveFile.SHORTCUT, "prot", "drive0", "");
    server.file("bild", "Skizze", "application/vnd.google-apps.drawing", "prot", "drive0", "");

    FileSyncHarness.Run run = harness.fullSync(store());

    assertThat(run.eventsOf(IndexingEventCategory.UNSUPPORTED_FORMAT))
        .extracting(IndexingRunEvent::getMessage)
        .containsExactlyInAnyOrder(
            "1" + DriveFileStore.SHORTCUTS_NOTE, "1" + DriveFileStore.NO_EXPORT_NOTE);
    assertThat(server.requests()).noneMatch(request -> request.startsWith("files/kurz"));
    assertThat(server.requests()).noneMatch(request -> request.startsWith("files/bild"));
  }

  @Test
  void aLockedDownloadIsSkippedWithoutARequest() {
    server.file("gesperrt", "Vertrag.txt", "text/plain", "prot", "drive0", "Geheim.").canDownload =
        false;

    FileSyncHarness.Run run = harness.fullSync(store());

    assertThat(run.eventsOf(IndexingEventCategory.REJECTED))
        .extracting(IndexingRunEvent::getMessage)
        .containsExactly(DriveFileStore.DOWNLOAD_LOCKED);
    assertThat(server.requests()).doesNotContain("files/gesperrt?alt=media");
  }

  @Test
  void throttledRequestsAreWaitedOutAndRetried() {
    server.failNext("files", 429, "rateLimitExceeded", 1);
    server.failNext("files/bericht", 403, "userRateLimitExceeded", 1);

    FileSyncHarness.Run run = harness.fullSync(store());

    assertThat(run.failure()).isNull();
    assertThat(run.ingested()).contains(path("bericht"), path("notiz"));
    assertThat(run.eventsOf(IndexingEventCategory.RATE_LIMITED)).hasSize(1);
  }

  @Test
  void aForbiddenFileThatIsNoThrottleIsUnreadableNotRetried() {
    server.failNext("files/bericht", 403, "forbidden", 1);

    FileSyncHarness.Run run = harness.fullSync(store());

    assertThat(run.failure()).isNull();
    assertThat(run.eventsOf(IndexingEventCategory.REJECTED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(path("bericht"));
    assertThat(server.requests().stream().filter(r -> r.startsWith("files/bericht"))).hasSize(1);
  }

  @Test
  void theDailyLimitEndsTheRun() {
    server.failNext("files", 403, "dailyLimitExceeded", 100);

    FileSyncHarness.Run run = harness.fullSync(store());

    assertThat(run.failure()).contains("Tageskontingent");
  }

  @Test
  void theFolderChainStartsWithTheScopeNameAndTheIdIsTheDeepLink() throws Exception {
    DriveFileStore store = store();

    var entry =
        store.list(new io.opaa.indexing.filesync.FileContainer("drive:drive0"), null).entries();

    assertThat(entry.get(0).filePath()).isEqualTo("https://drive.google.com/open?id=bericht");
    assertThat(entry.get(0).folder().segments()).containsExactly("Ablage", "Protokolle");
    assertThat(entry.get(0).changeMarker()).startsWith("m:").endsWith("|12");
  }

  @Test
  void aChangeRunFetchesChangedAndNewFilesAndRemovesDeletedAndMovedOutOnes() {
    server.file("weg", "Weg.txt", "text/plain", "folder1", null, "Wandert aus.");
    harness.fullSync(store());
    assertThat(harness.state().canReadChanges(STREAMS, WEEK, FileSyncHarness.NOW)).isTrue();

    FakeDriveServer.Item bericht = server.get("bericht");
    bericht.content = "Ein geänderter Bericht.".getBytes();
    bericht.modifiedTime = Instant.now();
    server.changed("bericht", "drive0");
    server.file("neu", "Neu.txt", "text/plain", "prot", "drive0", "Neu.");
    server.changed("neu", "drive0");
    server.removed("notiz", null);
    server.get("weg").parent = FakeDriveServer.ROOT_ID;
    server.changed("weg", null);
    server.requests().clear();

    FileSyncHarness.Run run = harness.changeRun(store());

    assertThat(run.failure()).isNull();
    assertThat(run.ingested()).containsExactlyInAnyOrder(path("bericht"), path("neu"));
    assertThat(run.eventsOf(IndexingEventCategory.REMOVED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactlyInAnyOrder(path("notiz"), path("weg"));
    assertThat(server.requests()).noneMatch(request -> request.equals("files"));
    assertThat(harness.state().canReadChanges(STREAMS, WEEK, FileSyncHarness.NOW)).isTrue();
  }

  @Test
  void aChangedFolderMakesTheNextRunAFullSync() {
    harness.fullSync(store());
    server.get("prot").name = "Protokolle alt";
    server.changed("prot", "drive0");

    harness.changeRun(store());

    assertThat(harness.state().canReadChanges(STREAMS, WEEK, FileSyncHarness.NOW)).isFalse();
  }

  @Test
  void anExpiredCursorDropsTheStreamsAndTheNextRunIsAFullSync() {
    harness.fullSync(store());
    server.expireCursors();

    FileSyncHarness.Run run = harness.changeRun(store());

    assertThat(run.failure()).isNull();
    assertThat(harness.state().changeCursors()).isEmpty();
    assertThat(harness.state().canReadChanges(STREAMS, WEEK, FileSyncHarness.NOW)).isFalse();
  }

  @Test
  void aRemovalInADriveTheAccountNoLongerSeesIsNoDeletionFinding() {
    harness.fullSync(store());
    server.removed("bericht", "drive0");
    server.failNext("drives/drive0", 404, "notFound", 100);

    FileSyncHarness.Run run = harness.changeRun(store());

    assertThat(run.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
    assertThat(harness.stored(path("bericht"))).isPresent();
  }

  private DriveFileStore store() {
    DriveApiFactory apis =
        new DriveApiFactory(
            GoogleDriveProperties.defaults(), TargetAddressValidator.disabled(), wait -> {});
    SourceSettings settings =
        new SourceSettings(null, server.base().toString(), null, null, false, null);
    GoogleDriveSettings driveSettings =
        GoogleDriveSettings.read(
            ConnectorData.of(
                Map.of("scopes", List.of(Map.of("drive", "drive0"), Map.of("folder", "folder1")))));
    return new DriveFileStore(
        apis.open(settings, () -> FakeDriveServer.TOKEN, RequestBudget.unbounded()),
        driveSettings,
        2);
  }

  private static String path(String id) {
    return DriveFileStore.OPEN_PREFIX + id;
  }
}
