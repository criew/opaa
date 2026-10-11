package io.opaa.indexing.source.dropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.indexing.filesync.Change;
import io.opaa.indexing.filesync.ChangeFeed;
import io.opaa.indexing.filesync.ChangePage;
import io.opaa.indexing.filesync.Exclusion;
import io.opaa.indexing.filesync.FetchedFile;
import io.opaa.indexing.filesync.FileAccessException;
import io.opaa.indexing.filesync.FileContainer;
import io.opaa.indexing.filesync.FileEntry;
import io.opaa.indexing.filesync.FilePage;
import io.opaa.indexing.source.ConnectorData;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the Dropbox store adds to the contracts: a deleted folder is a change of structure, a
 * deleted file is not; a Paper document is exported as Markdown, a file Dropbox does not hand out
 * is no document; the folder chain and several folders; the team space; a throttle waited out; a
 * name outside ASCII, escaped wherever it travels in a header.
 */
class DropboxFileStoreTest {

  private static final FileContainer AKTEN = new FileContainer("/Akten");

  private FakeDropboxServer server;

  @BeforeEach
  void setUp() {
    server = new FakeDropboxServer();
    server.folder("/Akten");
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  @Test
  void aDeletedFolderIsAChangeOfStructureAndADeletedFileIsNot() throws Exception {
    server.file("/Akten/2025/alt.txt", "Alt.");
    server.file("/Akten/neu.txt", "Neu.");
    DropboxFileStore store = store("/Akten");
    ChangeFeed feed = store.changes().orElseThrow();
    String start = feed.startCursor(feed.feedKey(AKTEN));

    server.remove("/Akten/neu.txt");
    server.changed("/Akten/neu.txt");
    ChangePage fileGone = feed.read(feed.feedKey(AKTEN), start);
    server.remove("/Akten/2025");
    server.changed("/Akten/2025");
    ChangePage folderGone = feed.read(feed.feedKey(AKTEN), fileGone.newStart());

    assertThat(fileGone.changes())
        .containsExactly(new Change.Removed(DropboxFileStore.OPEN_PREFIX + "/akten/neu.txt"));
    assertThat(fileGone.fullSyncNeeded()).isFalse();
    assertThat(folderGone.fullSyncNeeded()).as("Dropbox names no file below it").isTrue();
  }

  @Test
  void aMovedFolderReportsItsFilesAtTheNewPlaceAndAsksForAFullSync() throws Exception {
    server.file("/Akten/Alt/brief.txt", "Brief.");
    DropboxFileStore store = store("/Akten");
    ChangeFeed feed = store.changes().orElseThrow();
    String start = feed.startCursor(feed.feedKey(AKTEN));

    server.move("/Akten/Alt", "/Akten/Neu");
    ChangePage page = feed.read(feed.feedKey(AKTEN), start);

    assertThat(page.fullSyncNeeded()).isTrue();
    assertThat(page.changes())
        .filteredOn(Change.Updated.class::isInstance)
        .map(change -> ((Change.Updated) change).entry().filePath())
        .containsExactly(DropboxFileStore.OPEN_PREFIX + "/akten/neu/brief.txt");
  }

  @Test
  void aPaperDocumentIsExportedAsMarkdownAndAnUndownloadableFileIsNoDocument() throws Exception {
    server.file("/Akten/Protokoll.paper", "# Protokoll").paper = true;
    server.file("/Akten/Verknüpfung.web", "x").notDownloadable = true;
    DropboxFileStore store = store("/Akten");

    List<FileEntry> entries = listAll(store, AKTEN);
    FileEntry paper = byName(entries, "Protokoll.md");
    FetchedFile exported = store.fetch(paper, 1024);

    assertThat(paper.size()).isEqualTo(-1);
    assertThat(Files.readString(exported.file())).isEqualTo("# Protokoll");
    assertThat(byName(entries, "Verknüpfung.web").exclusion())
        .isInstanceOf(Exclusion.NotADocument.class);
    assertThat(server.requests()).contains("files/export");
    Files.deleteIfExists(exported.file());
  }

  @Test
  void theFolderChainMirrorsTheFoldersBelowAndSeveralFoldersStartWithTheirName() throws Exception {
    server.file("/Akten/2026/März/bescheid.txt", "Bescheid.");
    server.file("/Projekte/plan.txt", "Plan.");

    FileEntry single = listAll(store("/Akten"), AKTEN).getFirst();
    FileEntry several = listAll(store("/Akten", "/Projekte"), AKTEN).getFirst();

    assertThat(single.folder().segments()).containsExactly("2026", "März");
    assertThat(single.context().hierarchyPath()).isEqualTo("2026 / März".replace(" / ", sep()));
    assertThat(single.filePath())
        .isEqualTo(DropboxFileStore.OPEN_PREFIX + "/akten/2026/märz/bescheid.txt");
    assertThat(several.folder().segments()).containsExactly("Akten", "2026", "März");
  }

  @Test
  void theTeamSpaceIsReadFromItsRootAndAnAccountWithoutOneEndsTheRun() throws Exception {
    assertThatThrownBy(() -> listAll(teamStore("/Team Akten"), new FileContainer("/Team Akten")))
        .isInstanceOf(FileAccessException.RunEnding.class)
        .hasMessageContaining("Team-Bereich");

    server.withTeamSpace();
    server.file(
        FakeDropboxServer.TEAM_ROOT,
        "/Team Akten/team.txt",
        "Team.".getBytes(StandardCharsets.UTF_8));
    List<FileEntry> entries = listAll(teamStore("/Team Akten"), new FileContainer("/Team Akten"));

    assertThat(entries).extracting(FileEntry::fileName).containsExactly("team.txt");
  }

  @Test
  void aThrottledRequestWaitsAsToldAndIsSentAgain() throws Exception {
    server.file("/Akten/a.txt", "A.");
    server.throttleNext(2);

    List<FileEntry> entries = listAll(store("/Akten"), AKTEN);

    assertThat(entries).extracting(FileEntry::fileName).containsExactly("a.txt");
    assertThat(server.requests().subList(0, 3)).containsOnly("files/get_metadata");
  }

  @Test
  void aNameOutsideAsciiIsFetchedAndHeaderArgumentsAreEscaped() throws Exception {
    server.file("/Akten/Größe – Übersicht.txt", "Inhalt.");
    DropboxFileStore store = store("/Akten");

    FetchedFile fetched = store.fetch(listAll(store, AKTEN).getFirst(), 1024);

    assertThat(Files.readString(fetched.file())).isEqualTo("Inhalt.");
    assertThat(DropboxApi.asciiJson(Map.of("path", "/Größe")))
        .isEqualTo("{\"path\":\"/Gr\\u00f6\\u00dfe\"}");
    Files.deleteIfExists(fetched.file());
  }

  @Test
  void aFileNoLongerThereIsGoneAndOneMovedOutOfTheFolderToo() throws Exception {
    FakeDropboxServer.Item file = server.file("/Akten/a.txt", "A.");
    DropboxFileStore store = store("/Akten");

    server.move("/Akten/a.txt", "/Archiv/a.txt");
    assertThatThrownBy(() -> store.head(AKTEN, file.id))
        .isInstanceOf(FileAccessException.Gone.class);
    server.remove("/Archiv/a.txt");
    assertThatThrownBy(() -> store.head(AKTEN, file.id))
        .isInstanceOf(FileAccessException.Gone.class);
  }

  private DropboxFileStore store(String... folders) {
    return new DropboxFileStore(
        DropboxFileStoreContractTest.api(server, FakeDropboxServer.TOKEN),
        DropboxSettings.read(ConnectorData.of(Map.of("folders", List.of(folders)))),
        2);
  }

  private DropboxFileStore teamStore(String folder) {
    return new DropboxFileStore(
        DropboxFileStoreContractTest.api(server, FakeDropboxServer.TOKEN),
        DropboxSettings.read(
            ConnectorData.of(Map.of("folders", List.of(folder), "teamSpace", true))),
        2);
  }

  private static List<FileEntry> listAll(DropboxFileStore store, FileContainer container)
      throws Exception {
    List<FileEntry> entries = new ArrayList<>();
    String next = null;
    do {
      FilePage page = store.list(container, next);
      entries.addAll(page.entries());
      next = page.next();
    } while (next != null);
    return entries;
  }

  private static FileEntry byName(List<FileEntry> entries, String name) {
    return entries.stream().filter(e -> e.fileName().equals(name)).findFirst().orElseThrow();
  }

  private static String sep() {
    return io.opaa.knowledge.SourceDocumentContext.HIERARCHY_SEPARATOR;
  }
}
