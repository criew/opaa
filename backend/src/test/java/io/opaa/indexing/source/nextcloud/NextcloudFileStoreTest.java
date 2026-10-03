package io.opaa.indexing.source.nextcloud;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.filesync.FileSyncHarness;
import io.opaa.indexing.job.IndexingEventCategory;
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
}
