package io.opaa.indexing.source.sharepoint;

import static io.opaa.indexing.source.sharepoint.SharePointTestStores.DRIVE_0;
import static io.opaa.indexing.source.sharepoint.SharePointTestStores.DRIVE_1;
import static io.opaa.indexing.source.sharepoint.SharePointTestStores.SITE;
import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.filesync.FileStore;
import io.opaa.indexing.filesync.FileSyncHarness;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingRunEvent;
import io.opaa.indexing.source.RequestBudget;
import io.opaa.msgraph.FakeGraphServer;
import io.opaa.sourceaccess.SourceRequestMeter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * What the SharePoint store adds to the contracts (ADR-0040, Nachtrag „SharePoint“), run through
 * the file sync against the {@link FakeGraphServer}: only granted sites under {@code
 * Sites.Selected}, no OneDrive, the folder filter, renamed and moved files and folders, a chain
 * that no longer reaches the library, OneNote and malware, repeated delta entries, and durable
 * against passing download failures.
 */
class SharePointFileStoreTest {

  private static final String ROOT_0 = FakeGraphServer.rootId(DRIVE_0);

  private FakeGraphServer server;
  private FileSyncHarness harness;

  @BeforeEach
  void setUp() throws Exception {
    server = new FakeGraphServer();
    SharePointTestStores.twoLibraries(server);
    server.folder(DRIVE_0, "akten", "Akten", ROOT_0);
    server.folder(DRIVE_0, "intern", "Intern", ROOT_0);
    server.folder(DRIVE_0, "alt", "Alt", "akten");
    server.file(DRIVE_0, "bericht", "Bericht.txt", "akten", bytes("Ein Bericht."));
    server.file(DRIVE_0, "vermerk", "Vermerk.txt", "alt", bytes("Ein Vermerk."));
    server.file(DRIVE_0, "notiz", "Notiz.txt", "intern", bytes("Eine Notiz."));
    server.file(DRIVE_0, "oben", "Oben.txt", ROOT_0, bytes("Ganz oben."));
    harness = new FileSyncHarness();
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  @Test
  void underSitesSelectedOnlyTheGrantedSiteIsListedAndNothingElseIsRemoved() {
    server.site("fremd,site-2,web-2", "contoso.sharepoint.com", "/sites/fremd", "Fremd");
    server.drive("fremd,site-2,web-2", "b!fremd", "Fremd", "documentLibrary");
    server.file("b!fremd", "geheim", "Geheim.txt", FakeGraphServer.rootId("b!fremd"), bytes("x"));
    server.grantOnly(SITE);

    FileSyncHarness.Run run =
        harness.fullSync(
            store(libraries(Map.of("driveId", DRIVE_0), Map.of("driveId", "b!fremd"))));

    assertThat(run.failure()).isNull();
    assertThat(run.listingComplete()).isFalse();
    assertThat(run.unlistedContainerKeys()).containsExactly("drive:b!fremd");
    assertThat(run.eventsOf(IndexingEventCategory.REJECTED))
        .extracting(IndexingRunEvent::getMessage)
        .anySatisfy(message -> assertThat(message).contains("Sites.Selected"));
    assertThat(run.ingested()).contains(path("bericht")).doesNotContain(path("b!fremd", "geheim"));
    assertThat(server.requests()).noneMatch(request -> request.startsWith("/v1.0/sites"));
  }

  @Test
  void aDriveThatIsNoDocumentLibraryIsNotListed() {
    server.drive(SITE, "b!onedrive", "OneDrive", "business");
    server.file(
        "b!onedrive", "privat", "Privat.txt", FakeGraphServer.rootId("b!onedrive"), bytes("x"));

    FileSyncHarness.Run run = harness.fullSync(store(libraries(Map.of("driveId", "b!onedrive"))));

    assertThat(run.unlistedContainerKeys()).containsExactly("drive:b!onedrive");
    assertThat(run.eventsOf(IndexingEventCategory.REJECTED))
        .extracting(IndexingRunEvent::getMessage)
        .anySatisfy(message -> assertThat(message).contains(SharePointFileStore.NOT_A_LIBRARY));
    assertThat(run.ingested()).isEmpty();
    assertThat(server.requests()).noneMatch(request -> request.contains("/content"));
  }

  @Test
  void aFolderFilterKeepsOnlyTheFilesBelowItsFolders() {
    FileSyncHarness.Run run = harness.fullSync(filtered("akten"));

    assertThat(run.failure()).isNull();
    assertThat(run.listingComplete()).isTrue();
    assertThat(run.ingested()).containsExactlyInAnyOrder(path("bericht"), path("vermerk"));
    assertThat(harness.stored(path("vermerk")).orElseThrow().getSourceHierarchyPath())
        .isEqualTo("Akten / Alt");
  }

  @Test
  void aFileMovedOutOfTheFolderFilterIsRemovedByTheChangeRun() {
    harness.fullSync(filtered("akten"));

    server.move("bericht", "intern");
    FileSyncHarness.Run run = harness.changeRun(filtered("akten"));

    assertThat(run.failure()).isNull();
    assertThat(run.eventsOf(IndexingEventCategory.REMOVED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(path("bericht"));
    assertThat(run.ingested()).isEmpty();
  }

  @Test
  void aFilterFolderThatIsGoneKeepsTheBestandInsteadOfRemovingIt() {
    harness.fullSync(filtered("akten"));

    server.delete("akten");
    FileSyncHarness.Run run = harness.fullSync(filtered("akten"));

    assertThat(run.listingComplete()).isFalse();
    assertThat(run.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
    assertThat(harness.storedPaths()).containsExactlyInAnyOrder(path("bericht"), path("vermerk"));
  }

  @Test
  void aRenamedOrMovedFileKeepsItsDocumentAndTakesItsNewPlace() {
    harness.fullSync(all());
    var before = harness.stored(path("bericht")).orElseThrow();

    server.rename("bericht", "Jahresbericht.txt");
    server.move("bericht", "intern");
    FileSyncHarness.Run run = harness.changeRun(all());

    assertThat(run.failure()).isNull();
    assertThat(run.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
    var after = harness.stored(path("bericht")).orElseThrow();
    assertThat(after).isSameAs(before);
    assertThat(after.getFileName()).isEqualTo("Jahresbericht.txt");
    assertThat(after.getSourceHierarchyPath()).isEqualTo("Intern");
  }

  @Test
  void aRenamedFolderFetchesNothingAndRemovesNothing() {
    harness.fullSync(all());

    server.rename("akten", "Archiv");
    FileSyncHarness.Run changes = harness.changeRun(all());
    FileSyncHarness.Run full = harness.fullSync(all());

    assertThat(changes.ingested()).isEmpty();
    assertThat(full.ingested()).isEmpty();
    assertThat(full.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
    assertThat(harness.storedPaths()).contains(path("bericht"), path("vermerk"));
  }

  @Test
  void aFileBelowADeletedFolderIsRemovedByTheChangeRun() {
    harness.fullSync(all());

    server.delete("alt");
    server.touch("vermerk");
    FileSyncHarness.Run run = harness.changeRun(all());

    assertThat(run.failure()).isNull();
    assertThat(run.eventsOf(IndexingEventCategory.REMOVED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(path("vermerk"));
  }

  @Test
  void aFolderThatCannotBeReadForAMomentHoldsTheCursorUntilTheNextRun() {
    harness.fullSync(all());
    Map<String, String> before = harness.state().changeCursors();

    server.update("vermerk", bytes("Ein geänderter, längerer Vermerk."));
    server.failNext("items/alt", 500, "generalException", null, 1);
    FileSyncHarness.Run failed = harness.changeRun(all());
    FileSyncHarness.Run retried = harness.changeRun(all());

    assertThat(failed.ingested()).isEmpty();
    assertThat(harness.state().changeCursors().get("drive:" + DRIVE_0))
        .isNotEqualTo(before.get("drive:" + DRIVE_0));
    assertThat(retried.ingested()).containsExactly(path("vermerk"));
  }

  @Test
  void oneNoteNotebooksAndTheirSectionsAreNoDocumentsAndNeverFetched() {
    server.folder(DRIVE_0, "buch", "Notizbuch", ROOT_0).packageType = "oneNote";
    server.file(DRIVE_0, "abschnitt", "Abschnitt.one", "buch", bytes("x"));

    FileSyncHarness.Run run = harness.fullSync(all());

    assertThat(run.eventsOf(IndexingEventCategory.UNSUPPORTED_FORMAT))
        .extracting(IndexingRunEvent::getMessage)
        .containsExactly("2" + SharePointFileStore.ONENOTE_NOTE);
    assertThat(server.requests()).noneMatch(request -> request.contains("abschnitt/content"));
  }

  @Test
  void aFileFlaggedAsMalwareIsSkippedWithoutADownload() {
    server.file(DRIVE_0, "boese", "Boese.txt", "akten", bytes("x")).malware = true;

    FileSyncHarness.Run run = harness.fullSync(all());

    assertThat(run.eventsOf(IndexingEventCategory.REJECTED))
        .extracting(IndexingRunEvent::getMessage, IndexingRunEvent::getReference)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(SharePointFileStore.MALWARE, path("boese")));
    assertThat(server.requests()).noneMatch(request -> request.contains("boese/content"));
    assertThat(run.listingComplete()).isTrue();
  }

  @Test
  void anItemDeltaRepeatsIsListedOnce() {
    server.repeatDeltaEntries();

    FileSyncHarness.Run run = harness.fullSync(all());

    assertThat(run.ingested()).hasSize(4).doesNotHaveDuplicates();
    assertThat(run.eventsOf(IndexingEventCategory.SUMMARY))
        .extracting(IndexingRunEvent::getMessage)
        .anySatisfy(message -> assertThat(message).contains("4 Einträge gelistet"));
  }

  /**
   * The download host refusing one file for good is no passing failure: the change run moves its
   * cursor on, and a round over several runs reaches its end.
   */
  @Test
  void aFileTheDownloadHostRefusesForGoodHoldsNeitherTheCursorNorTheRound() {
    harness.fullSync(all());
    Map<String, String> before = harness.state().changeCursors();
    server.update("bericht", bytes("Eine neue, längere Fassung des Berichts."));
    server.failNext("/blob/bericht", 403, null, null, 1_000);

    FileSyncHarness.Run changes = harness.changeRun(all());

    assertThat(changes.eventsOf(IndexingEventCategory.REJECTED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(path("bericht"));
    assertThat(harness.state().changeCursors().get("drive:" + DRIVE_0))
        .isNotEqualTo(before.get("drive:" + DRIVE_0));

    for (int i = 0; i < 12; i++) {
      server.folder(DRIVE_0, "mehr" + i, "Mehr " + i, ROOT_0);
      server.file(DRIVE_0, "datei" + i, "Datei " + i + ".txt", "mehr" + i, bytes("Text " + i));
    }
    boolean ended = false;
    for (int runs = 0; runs < 20 && !ended; runs++) {
      FileSyncHarness.Run run = harness.fullSync(budgeted(8));
      assertThat(run.failure()).isNull();
      ended = !harness.state().isFullSyncInterrupted();
    }
    assertThat(ended).as("the round reaches its end despite the refused file").isTrue();
  }

  @ParameterizedTest
  @CsvSource({"500, 1", "429, 6"})
  void aPassingDownloadFailureHoldsTheCursorForTheNextRun(int status, int times) {
    harness.fullSync(all());
    Map<String, String> before = harness.state().changeCursors();
    server.update("bericht", bytes("Eine neue, längere Fassung des Berichts."));
    server.failNext("/blob/bericht", status, null, null, times);

    FileSyncHarness.Run failed = harness.changeRun(all());
    FileSyncHarness.Run retried = harness.changeRun(all());

    assertThat(failed.failed()).isEqualTo(1);
    assertThat(retried.ingested()).containsExactly(path("bericht"));
    assertThat(harness.state().changeCursors().get("drive:" + DRIVE_0))
        .isNotEqualTo(before.get("drive:" + DRIVE_0));
  }

  @Test
  void aThrottledDeltaIsWaitedOutAndChargedToTheBudget() {
    server.failNext("root/delta", 429, "TooManyRequests", "2", 1);
    RequestBudget budget = RequestBudget.unbounded();

    FileSyncHarness.Run run =
        harness.fullSync(SharePointTestStores.store(server, settings(), 3, budget));

    assertThat(run.failure()).isNull();
    assertThat(run.listingComplete()).isTrue();
    assertThat(budget.meter().throttles()).isEqualTo(1);
  }

  @Test
  void withSeveralLibrariesEachDocumentBelongsToItsLibrary() {
    server.file(DRIVE_1, "akte", "Akte.txt", FakeGraphServer.rootId(DRIVE_1), bytes("Eine Akte."));

    harness.fullSync(
        SharePointTestStores.store(
            server, SharePointTestStores.bothLibraries(), 3, RequestBudget.unbounded()));

    assertThat(harness.stored(path(DRIVE_1, "akte")).orElseThrow().getSourceContainerKey())
        .isEqualTo("drive:" + DRIVE_1);
    assertThat(harness.stored(path("vermerk")).orElseThrow().getSourceHierarchyPath())
        .as("the hierarchy path stays below the library")
        .isEqualTo("Akten / Alt");
  }

  @Test
  void theChangeMarkerFitsItsColumnAndFollowsThePlace() {
    DriveItem item =
        new DriveItem(
            "id",
            "Bericht.txt",
            "akten",
            false,
            false,
            false,
            null,
            false,
            12,
            null,
            "\"c:{F2A3D9BA-ABCD-4F2E-9B1A-1234567890AB},123\"",
            null,
            null);
    DriveItem moved =
        new DriveItem(
            "id",
            "Bericht.txt",
            "intern",
            false,
            false,
            false,
            null,
            false,
            12,
            null,
            item.cTag(),
            null,
            null);

    assertThat(SharePointFileStore.marker(item)).startsWith("c:").hasSizeLessThanOrEqualTo(64);
    assertThat(SharePointFileStore.marker(moved)).isNotEqualTo(SharePointFileStore.marker(item));
  }

  private SharePointSettings settings() {
    return libraries(Map.of("driveId", DRIVE_0));
  }

  private static SharePointSettings libraries(Map<?, ?>... libraries) {
    return SharePointTestStores.settings(Map.of("libraries", List.of(libraries)));
  }

  private FileStore all() {
    return SharePointTestStores.store(server, settings(), 3, RequestBudget.unbounded());
  }

  private FileStore filtered(String folder) {
    return SharePointTestStores.store(
        server,
        libraries(Map.of("driveId", DRIVE_0, "folders", List.of(folder))),
        3,
        RequestBudget.unbounded());
  }

  private FileStore budgeted(int requests) {
    return SharePointTestStores.store(
        server, settings(), 3, new RequestBudget(new SourceRequestMeter(), requests, null));
  }

  private FileStore store(SharePointSettings settings) {
    return SharePointTestStores.store(server, settings, 3, RequestBudget.unbounded());
  }

  private static String path(String id) {
    return path(DRIVE_0, id);
  }

  private static String path(String drive, String id) {
    return SharePointFileStore.filePath(drive, id);
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
