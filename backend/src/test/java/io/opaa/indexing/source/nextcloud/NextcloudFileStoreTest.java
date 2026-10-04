package io.opaa.indexing.source.nextcloud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.indexing.filesync.FileAccessException;
import io.opaa.indexing.filesync.FileContainer;
import io.opaa.indexing.filesync.FileEntry;
import io.opaa.indexing.filesync.FileStore;
import io.opaa.indexing.filesync.FileSyncHarness;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingRunEvent;
import io.opaa.knowledge.Document;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the Nextcloud store adds to the contract: unchanged trees spared by their folder ETags, and
 * renames that keep a document, measured in requests against {@link FakeNextcloudServer}.
 */
class NextcloudFileStoreTest {

  private static final List<String> FOLDERS = List.of("/Projekte", "/Archiv");

  private FakeNextcloudServer server;
  private FileSyncHarness harness;

  @BeforeEach
  void setUp() throws Exception {
    server = new FakeNextcloudServer("");
    harness = new FileSyncHarness();
    server
        .put("Projekte/a.txt", "A.")
        .put("Projekte/Akten/2026/b.txt", "B.")
        .put("Projekte/Akten/2025/c.txt", "C.")
        .put("Archiv/d.txt", "D.");
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private FileSyncHarness.Run fullSync() {
    server.clearRequests();
    return harness.fullSync(
        NextcloudTestStores.open(
            NextcloudTestStores.settings(server.baseUrl(), server.credentials(), FOLDERS)));
  }

  @Test
  void anUnchangedFolderTreeCostsOneRequestPerConfiguredFolder() {
    assertThat(fullSync().ingested()).hasSize(4);

    FileSyncHarness.Run run = fullSync();

    assertThat(server.requests())
        .containsExactly("PROPFIND principal", "PROPFIND 1 Projekte", "PROPFIND 1 Archiv");
    assertThat(run.listingComplete()).isTrue();
    assertThat(run.processed() + run.skipped()).isZero();
    assertThat(harness.storedPaths()).hasSize(4);
  }

  @Test
  void aChangeDeepDownListsOnlyTheFoldersOnItsPath() {
    fullSync();
    server.put("Projekte/Akten/2026/b.txt", "B, zweite Fassung.");

    FileSyncHarness.Run run = fullSync();

    assertThat(server.requests())
        .containsExactly(
            "PROPFIND principal",
            "PROPFIND 1 Projekte",
            "PROPFIND 1 Projekte/Akten",
            "PROPFIND 1 Projekte/Akten/2026",
            "GET Projekte/Akten/2026/b.txt",
            "PROPFIND 1 Archiv");
    assertThat(run.processed()).isEqualTo(1);
    assertThat(run.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
  }

  @Test
  void aRenamedFolderKeepsItsDocumentsUnderTheirFileIds() {
    fullSync();
    String filePath =
        server.baseUrl() + "/index.php/f/" + server.fileId("Projekte/Akten/2026/b.txt");

    server.move("Projekte/Akten", "Projekte/Vorgänge");
    FileSyncHarness.Run run = fullSync();

    assertThat(run.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
    assertThat(harness.storedPaths()).hasSize(4).contains(filePath);
    Document moved = harness.stored(filePath).orElseThrow();
    assertThat(moved.getSourceContainerKey()).isEqualTo("/Projekte");
    assertThat(moved.getSourceHierarchyPath()).isEqualTo("Vorgänge / 2026");

    FileSyncHarness.Run after = fullSync();
    assertThat(after.eventsOf(IndexingEventCategory.REMOVED))
        .as("the renamed folder's ETag is remembered under its new path")
        .isEmpty();
    assertThat(server.requests())
        .containsExactly("PROPFIND principal", "PROPFIND 1 Projekte", "PROPFIND 1 Archiv");
  }

  @Test
  void aFileMovedIntoAnotherConfiguredFolderStaysOneDocument() {
    fullSync();
    String filePath = server.baseUrl() + "/index.php/f/" + server.fileId("Projekte/a.txt");

    server.move("Projekte/a.txt", "Archiv/a.txt");
    fullSync();
    FileSyncHarness.Run after = fullSync();

    assertThat(harness.stored(filePath).orElseThrow().getSourceContainerKey()).isEqualTo("/Archiv");
    assertThat(after.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
    assertThat(harness.storedPaths()).hasSize(4);
  }

  @Test
  void namesWithSpacesPlusParenthesesColonsAtSignsAndUmlautsAreListedAndFetched() {
    String odd = "Projekte/Akten (alt) + neu/Bär: Vertrag @ 2026.txt";
    server.put(odd, "Sonderzeichen.");

    FileSyncHarness.Run first = fullSync();
    FileSyncHarness.Run second = fullSync();

    assertThat(first.ingested()).contains(server.baseUrl() + "/index.php/f/" + server.fileId(odd));
    assertThat(server.requests()).contains("PROPFIND 1 Projekte");
    assertThat(second.processed() + second.skipped()).isZero();
    assertThat(
            harness
                .stored(server.baseUrl() + "/index.php/f/" + server.fileId(odd))
                .orElseThrow()
                .getSourceHierarchyPath())
        .isEqualTo("Akten (alt) + neu");
  }

  @Test
  void aFileTheInstanceCannotOpenKeepsItsStoredVersion() {
    fullSync();
    String filePath = server.baseUrl() + "/index.php/f/" + server.fileId("Archiv/d.txt");
    String marker = harness.stored(filePath).orElseThrow().getLastModifiedRemote();
    server.put("Archiv/d.txt", "D, zweite Fassung.").unopenable("Archiv/d.txt");

    FileSyncHarness.Run run = fullSync();

    assertThat(run.failure()).isNull();
    assertThat(run.eventsOf(IndexingEventCategory.REJECTED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(filePath);
    assertThat(harness.stored(filePath).orElseThrow().getLastModifiedRemote()).isEqualTo(marker);
  }

  @Test
  void aFolderRenamedDuringTheRunKeepsItsDocuments() {
    fullSync();
    server.put("Projekte/Akten/2026/b.txt", "B, geändert.").vanishOnVisit("Projekte/Akten");

    FileSyncHarness.Run run = fullSync();

    assertThat(run.listingComplete()).isFalse();
    assertThat(run.unlistedContainerKeys()).containsExactly("/Projekte");
    assertThat(run.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
    assertThat(harness.storedPaths()).hasSize(4);
  }

  @Test
  void anAnswerOverItsSizeBoundLeavesOnlyItsFolderUnlistedAndNamesTheSetting() {
    fullSync();
    server.put("Archiv/neu.txt", "Neu.");

    server.clearRequests();
    FileSyncHarness.Run run =
        harness.fullSync(
            NextcloudTestStores.open(
                NextcloudTestStores.settings(server.baseUrl(), server.credentials(), FOLDERS),
                600));

    assertThat(run.failure()).as("the other folders still run").isNull();
    assertThat(run.listingComplete()).isFalse();
    assertThat(run.eventsOf(IndexingEventCategory.REJECTED))
        .extracting(IndexingRunEvent::getMessage)
        .anyMatch(message -> message.contains("max-response-bytes"));
    assertThat(run.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
  }

  @Test
  void aFileAnsweredWithAForeignAddressIsSkippedAndNeverRequested() {
    for (String foreign :
        List.of(
            "https://andere.example/remote.php/dav/files/tech-uid/Archiv/d.txt",
            "@andere.example/remote.php/dav/files/tech-uid/Archiv/d.txt",
            "//andere.example/remote.php/dav/files/tech-uid/Archiv/d.txt",
            "/remote.php/dav/files/tech-uid/../andere/d.txt")) {
      setUpFresh();
      fullSync();
      String filePath = server.baseUrl() + "/index.php/f/" + server.fileId("Archiv/d.txt");
      server.put("Archiv/d.txt", "D, geändert.").answerFileHrefsWith(foreign);

      FileSyncHarness.Run run = fullSync();

      assertThat(run.failure()).as(foreign).isNull();
      assertThat(run.eventsOf(IndexingEventCategory.REJECTED))
          .as(foreign)
          .extracting(IndexingRunEvent::getReference)
          .contains(filePath);
      assertThat(server.requests()).as(foreign).noneMatch(request -> request.startsWith("GET"));
      assertThat(harness.storedPaths()).as(foreign).contains(filePath);
      server.close();
    }
  }

  /** A 403 at the sign-in ends the run wherever it surfaces, and is no rejection of the secret. */
  @Test
  void a403AtTheSignInEndsTheRunAlsoWhereOnlyOneFileIsLookedUp() throws Exception {
    server.denySignIn();

    try (FileStore store = open()) {
      assertThatThrownBy(() -> store.head(new FileContainer("/Projekte"), fileId("Projekte/a.txt")))
          .isInstanceOf(FileAccessException.RunEnding.class)
          .isNotInstanceOf(FileAccessException.CredentialsRejected.class)
          .hasMessage("Keine Leseberechtigung für die Anmeldung (HTTP 403).");
    }
  }

  @Test
  void a403OnAFileIsThatFilesFindingAlone() throws Exception {
    try (FileStore store = open()) {
      FileEntry entry = store.head(new FileContainer("/Projekte"), fileId("Projekte/a.txt"));
      server.denyReading("Projekte");

      assertThatThrownBy(() -> store.fetch(entry, 1024))
          .isInstanceOf(FileAccessException.Unreadable.class);
    }
  }

  private FileStore open() {
    return NextcloudTestStores.open(
        NextcloudTestStores.settings(server.baseUrl(), server.credentials(), FOLDERS));
  }

  private static String fileId(String path) {
    return "/remote.php/dav/files/" + FakeNextcloudServer.USER_ID + "/" + path;
  }

  private void setUpFresh() {
    try {
      server.close();
      setUp();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
