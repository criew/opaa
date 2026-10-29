package io.opaa.indexing.source.googledrive;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.filesync.FileSyncHarness;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingRunEvent;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.RequestBudget;
import io.opaa.indexing.spi.SourceSettings;
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

  /** ADR-0040, Entscheidung 8: the text export is the last try; past it the file is unreadable. */
  @Test
  void overTheExportLimitEvenAsTextADocIsRejectedAndTheRunGoesOn() {
    FakeDriveServer.Item doc =
        server.file(
            "doc", "Protokoll", "application/vnd.google-apps.document", "prot", "drive0", "x");
    doc.exportTooLarge = true;
    doc.textExportTooLarge = true;

    FileSyncHarness.Run run = harness.fullSync(store());

    assertThat(run.failure()).isNull();
    assertThat(run.listingComplete()).isTrue();
    assertThat(run.eventsOf(IndexingEventCategory.REJECTED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(path("doc"));
    assertThat(harness.stored(path("doc"))).isEmpty();
    assertThat(run.ingested()).contains(path("bericht"), path("notiz"));
    assertThat(server.requests().stream().filter("files/doc/export"::equals)).hasSize(2);
  }

  /**
   * ADR-0040, Entscheidung 8: only the reason {@code exportSizeLimitExceeded} leads to the text
   * export; any other refusal of an export makes the file unreadable.
   */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"cannotExportFile", "forbidden"})
  void anExportRefusedForAnotherReasonIsRejectedWithoutTextExport(String reason) {
    server.file("doc", "Protokoll", "application/vnd.google-apps.document", "prot", "drive0", "x");
    server.failNext("files/doc/export", 403, reason, 1);

    FileSyncHarness.Run run = harness.fullSync(store());

    assertThat(run.failure()).isNull();
    assertThat(run.eventsOf(IndexingEventCategory.REJECTED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(path("doc"));
    assertThat(run.eventsOf(IndexingEventCategory.FORMAT_MISMATCH)).isEmpty();
    assertThat(server.requests().stream().filter("files/doc/export"::equals)).hasSize(1);
    assertThat(run.ingested()).contains(path("bericht"), path("notiz"));
  }

  /**
   * ADR-0040, Entscheidung 7: a Google file keeps its marker while {@code modifiedTime} stays, so a
   * second full sync exports nothing, whether or not Google's exports are byte-identical.
   */
  @Test
  void anUnchangedGoogleDocIsNotExportedAgain() {
    server.file("doc", "Protokoll", "application/vnd.google-apps.document", "prot", "drive0", "x");
    harness.fullSync(store());
    server.requests().clear();

    FileSyncHarness.Run run = harness.fullSync(store());

    assertThat(run.failure()).isNull();
    assertThat(run.ingested()).doesNotContain(path("doc"));
    assertThat(server.requests()).doesNotContain("files/doc/export");
  }

  /**
   * ADR-0040, Entscheidung 7: a new {@code modifiedTime} exports the file again and hands it to the
   * intake, whose checksum decides whether it is indexed anew.
   */
  @Test
  void aGoogleDocWithANewModifiedTimeIsExportedAgainUnderANewMarker() {
    FakeDriveServer.Item doc =
        server.file(
            "doc", "Protokoll", "application/vnd.google-apps.document", "prot", "drive0", "x");
    harness.fullSync(store());
    String before = harness.stored(path("doc")).orElseThrow().getLastModifiedRemote();
    doc.modifiedTime = doc.modifiedTime.plusSeconds(60);
    server.requests().clear();

    FileSyncHarness.Run run = harness.fullSync(store());

    assertThat(run.ingested()).contains(path("doc"));
    assertThat(server.requests()).contains("files/doc/export");
    assertThat(harness.stored(path("doc")).orElseThrow().getLastModifiedRemote())
        .isNotEqualTo(before)
        .startsWith("g:")
        .endsWith("|docx");
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
    server.remove("notiz");
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

  /**
   * Google's account stream also reports changes of a shared drive that has its own stream; the
   * file stays a document of that drive, whichever stream is read first.
   */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void anEditInASharedDriveReportedByTheAccountStreamRemovesNothing(boolean driveFirst)
      throws Exception {
    harness.fullSync(store(driveFirst));
    FakeDriveServer.Item bericht = server.get("bericht");
    bericht.content = "Ein geänderter Bericht.".getBytes();
    bericht.modifiedTime = Instant.now();
    server.changed("bericht", "drive0");

    FileSyncHarness.Run run = harness.changeRun(store(driveFirst));

    assertThat(run.failure()).isNull();
    assertThat(run.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
    assertThat(harness.stored(path("bericht"))).isPresent();
  }

  @Test
  void aFileMovedBetweenTwoConfiguredDrivesStaysADocument() throws Exception {
    server.addDrive("drive2", "Zweite Ablage");
    DriveFileStore first = store(List.of(Map.of("drive", "drive2"), Map.of("drive", "drive0")));
    harness.fullSync(first);
    FakeDriveServer.Item bericht = server.get("bericht");
    bericht.driveId = "drive2";
    bericht.parent = "drive2";
    server.changed("bericht", "drive2");
    server.removed("bericht", "drive0");

    FileSyncHarness.Run run =
        harness.changeRun(store(List.of(Map.of("drive", "drive2"), Map.of("drive", "drive0"))));

    assertThat(run.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
    assertThat(harness.stored(path("bericht"))).isPresent();
  }

  @Test
  void aFileInTwoOverlappingScopesKeepsTheFolderOfTheFirst() {
    server.folder("sub", "Unterordner", "prot", "drive0");
    server.file("tief", "Tief.txt", "text/plain", "sub", "drive0", "Tief unten.");

    harness.fullSync(store(List.of(Map.of("drive", "drive0"), Map.of("folder", "sub"))));

    assertThat(harness.stored(path("tief")).orElseThrow().getSourceContainerKey())
        .isEqualTo("drive:drive0");
    assertThat(harness.stored(path("tief")).orElseThrow().getSourceHierarchyPath())
        .contains("Unterordner");
  }

  @Test
  void aDownloadRedirectedToAnotherHostIsRefusedAndHoldsNoCursor() throws Exception {
    try (FakeDriveServer foreign = new FakeDriveServer()) {
      harness.fullSync(store());
      String stored = harness.stored(path("bericht")).orElseThrow().getLastModifiedRemote();
      FakeDriveServer.Item bericht = server.get("bericht");
      bericht.content = "Neu.".getBytes();
      bericht.modifiedTime = Instant.now();
      bericht.redirectTo = "http://localhost:" + foreign.base().getPort() + "/drive/v3/files/x";
      server.changed("bericht", "drive0");
      var before = harness.state().changeCursors();

      FileSyncHarness.Run run = harness.changeRun(store());

      assertThat(run.eventsOf(IndexingEventCategory.REJECTED))
          .extracting(IndexingRunEvent::getReference)
          .contains(path("bericht"));
      assertThat(harness.stored(path("bericht")).orElseThrow().getLastModifiedRemote())
          .as("the stored version stays")
          .isEqualTo(stored);
      assertThat(foreign.requests()).as("no request, no token reaches the other host").isEmpty();
      assertThat(harness.state().changeCursors())
          .as("a refused redirect is no transient failure")
          .isNotEqualTo(before);
    }
  }

  /**
   * ADR-0040, Entscheidung 3: on a full sync a foreign redirect costs the new version of the one
   * file, not its stored version and not the run.
   */
  @Test
  void aDownloadRedirectedToAnotherHostOnAFullSyncIsRejectedAndTheRunGoesOn() {
    try (FakeDriveServer foreign = new FakeDriveServer()) {
      harness.fullSync(store());
      String stored = harness.stored(path("bericht")).orElseThrow().getLastModifiedRemote();
      FakeDriveServer.Item bericht = server.get("bericht");
      bericht.content = "Neu.".getBytes();
      bericht.modifiedTime = Instant.now();
      bericht.redirectTo =
          "http://localhost:" + foreign.base().getPort() + "/drive/v3/files/x?alt=media";

      FileSyncHarness.Run run = harness.fullSync(store());

      assertThat(run.failure()).isNull();
      assertThat(run.listingComplete()).isTrue();
      assertThat(run.eventsOf(IndexingEventCategory.REJECTED))
          .extracting(IndexingRunEvent::getReference)
          .containsExactly(path("bericht"));
      assertThat(harness.stored(path("bericht")).orElseThrow().getLastModifiedRemote())
          .as("the stored version stays")
          .isEqualTo(stored);
      assertThat(harness.stored(path("notiz"))).isPresent();
      assertThat(foreign.requests()).as("no request, no token reaches the other host").isEmpty();
    }
  }

  /**
   * ADR-0040, Entscheidung 3: a lookup in a change run that Drive redirects to another host is no
   * removal finding - neither for a parent folder nor for a file reported removed; the stream is
   * read again.
   */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void aLookupRedirectedToAnotherHostInAChangeRunRemovesNothing(boolean parentFolder) {
    try (FakeDriveServer foreign = new FakeDriveServer()) {
      harness.fullSync(store());
      String elsewhere = "http://localhost:" + foreign.base().getPort() + "/drive/v3/files/x";
      if (parentFolder) {
        server.get("bericht").modifiedTime = Instant.now();
        server.get("prot").metadataRedirectTo = elsewhere;
        server.changed("bericht", "drive0");
      } else {
        server.get("bericht").metadataRedirectTo = elsewhere;
        server.removed("bericht", "drive0");
      }
      var before = harness.state().changeCursors();

      FileSyncHarness.Run run = harness.changeRun(store());

      assertThat(run.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
      assertThat(harness.stored(path("bericht"))).isPresent();
      assertThat(foreign.requests()).as("no request, no token reaches the other host").isEmpty();
      assertThat(harness.state().changeCursors()).isEqualTo(before);
    }
  }

  /**
   * ADR-0040, Entscheidung 3: a redirect of {@code alt=media} within the API's origin is followed
   * with the token, and the file is the redirect target's.
   */
  @Test
  void aDownloadRedirectedWithinTheApiOriginIsFollowed() throws Exception {
    server.file("ziel", "Ziel.txt", "text/plain", "anderswo", null, "Vom Ziel.");
    server.get("bericht").redirectTo = server.base() + "/drive/v3/files/ziel?alt=media";
    DriveFileStore store = store();
    var entries =
        store.list(new io.opaa.indexing.filesync.FileContainer("drive:drive0"), null).entries();
    assertThat(entries).extracting(e -> e.id()).containsExactly("bericht");

    var fetched = store.fetch(entries.get(0), FileSyncHarness.MAX_FILE_SIZE);

    try {
      assertThat(java.nio.file.Files.readString(fetched.file())).isEqualTo("Vom Ziel.");
    } finally {
      java.nio.file.Files.deleteIfExists(fetched.file());
    }
    assertThat(server.requests())
        .containsSubsequence("files/bericht?alt=media", "files/ziel?alt=media");
    assertThat(server.tokens()).last().isEqualTo(FakeDriveServer.TOKEN);
  }

  @Test
  void aMalformedRequestIsNoExpiredCursor() {
    harness.fullSync(store());
    var before = harness.state().changeCursors();
    server.failChangesWith(400, "badRequest");

    FileSyncHarness.Run run = harness.changeRun(store());

    assertThat(run.failure()).isNull();
    assertThat(harness.state().changeCursors()).isEqualTo(before);
    assertThat(harness.state().canReadChanges(STREAMS, WEEK, FileSyncHarness.NOW)).isTrue();
  }

  @Test
  void aFileWhoseParentCannotBeReadIsPlacedAtTheRootAndTheRunGoesOn() {
    server.file("waise", "Waise.txt", "text/plain", "gibtsnicht", "drive0", "Ohne Ordner.");

    FileSyncHarness.Run run = harness.fullSync(store());

    assertThat(run.failure()).isNull();
    assertThat(run.ingested()).contains(path("waise"));
  }

  private DriveFileStore store(boolean driveFirst) {
    return driveFirst
        ? store()
        : store(List.of(Map.of("folder", "folder1"), Map.of("drive", "drive0")));
  }

  private DriveFileStore store(List<Map<String, Object>> scopes) {
    DriveApiFactory apis =
        new DriveApiFactory(
            GoogleDriveProperties.defaults(), TargetAddressValidator.disabled(), wait -> {});
    SourceSettings settings = new SourceSettings(null, server.base().toString(), null, null);
    return new DriveFileStore(
        apis.open(settings, () -> FakeDriveServer.TOKEN, RequestBudget.unbounded()),
        GoogleDriveSettings.read(ConnectorData.of(Map.of("scopes", scopes))),
        2);
  }

  private DriveFileStore store() {
    DriveApiFactory apis =
        new DriveApiFactory(
            GoogleDriveProperties.defaults(), TargetAddressValidator.disabled(), wait -> {});
    SourceSettings settings = new SourceSettings(null, server.base().toString(), null, null);
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
