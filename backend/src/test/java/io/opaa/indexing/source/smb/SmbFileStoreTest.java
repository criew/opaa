package io.opaa.indexing.source.smb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.indexing.filesync.FetchedFile;
import io.opaa.indexing.filesync.FileAccessException;
import io.opaa.indexing.filesync.FileContainer;
import io.opaa.indexing.filesync.FileEntry;
import io.opaa.indexing.filesync.FilePage;
import io.opaa.indexing.filesync.FileStore;
import io.opaa.indexing.filesync.FileSyncHarness;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingRunEvent;
import io.opaa.indexing.job.RequestBudgetExhaustedException;
import io.opaa.indexing.source.RequestBudget;
import io.opaa.sourceaccess.SourceRequestMeter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * What the contract leaves to the connector, against a real Samba: links, unreadable and looping
 * folders, a folder larger than a page, names with special characters, the request budget.
 */
@Testcontainers(disabledWithoutDocker = true)
class SmbFileStoreTest {

  private static final AtomicInteger CASES = new AtomicInteger();

  private SambaFixture samba;
  private String folder;

  @BeforeEach
  void setUp() {
    samba = SambaFixture.get();
    folder = "Speicher " + CASES.incrementAndGet();
    samba.mkdirs(folder);
  }

  @Test
  void linksAreListedButNeitherFetchedNorFollowedAndALoopEndsAtTheFolderMetBefore()
      throws Exception {
    samba.put(folder + "/a.txt", "A.");
    samba.put(folder + "/unter/b.txt", "B.");
    samba.dfsLink(folder + "/dfs-verweis");
    // Samba resolves these itself: they reach the client as ordinary folders
    samba.symlink(".", folder + "/schleife");
    samba.symlink("..", folder + "/unter/zurueck");

    FileSyncHarness.Run run = new FileSyncHarness().fullSync(store(4));

    assertThat(run.failure()).isNull();
    assertThat(run.ingested()).containsExactlyInAnyOrder(path("a.txt"), path("unter/b.txt"));
    assertThat(run.listingComplete()).isTrue();
    assertThat(run.eventsOf(IndexingEventCategory.UNSUPPORTED_FORMAT))
        .extracting(IndexingRunEvent::getMessage)
        .anySatisfy(message -> assertThat(message).contains(SmbFileStore.LINK_NOTE));
  }

  @Test
  void aLinkToAFileOrFolderElsewhereInTheShareIsNeitherReadNorFollowed() throws Exception {
    String elsewhere = "Ziel von " + folder;
    samba.put(elsewhere + "/geheim.txt", "Nicht im Bereich.");
    samba.put(folder + "/a.txt", "A.");
    // Samba lists these as an ordinary file and folder; opening them is what must not follow them
    samba.symlink("../" + elsewhere + "/geheim.txt", folder + "/verweis.txt");
    samba.symlink("../" + elsewhere, folder + "/ordner-verweis");

    FileSyncHarness.Run run = new FileSyncHarness().fullSync(store(10));

    assertThat(run.failure()).isNull();
    assertThat(run.ingested()).containsExactly(path("a.txt"));
    assertThat(run.listingComplete()).isTrue();
    assertThat(run.eventsOf(IndexingEventCategory.UNSUPPORTED_FORMAT))
        .extracting(IndexingRunEvent::getMessage)
        .anySatisfy(message -> assertThat(message).contains(SmbFileStore.LINK_NOTE));
  }

  @Test
  void anUnreadableFolderIsNamedTheRestIsListedAndNothingInTheContainerIsRemoved()
      throws Exception {
    samba.put(folder + "/a.txt", "A.");
    samba.put(folder + "/geheim/x.txt", "X.");
    samba.put(folder + "/offen/b.txt", "B.");
    FileSyncHarness harness = new FileSyncHarness();
    harness.fullSync(store(2));

    samba.denyListing(folder + "/geheim");
    samba.remove(folder + "/a.txt");
    samba.put(folder + "/offen/c.txt", "C.");
    FileSyncHarness.Run run = harness.fullSync(store(2));

    assertThat(run.failure()).isNull();
    assertThat(run.ingested()).containsExactly(path("offen/c.txt"));
    assertThat(run.listingComplete()).isFalse();
    assertThat(run.eventsOf(IndexingEventCategory.REJECTED))
        .extracting(IndexingRunEvent::getMessage)
        .anySatisfy(message -> assertThat(message).contains("/" + folder + "/geheim"));
    assertThat(harness.storedPaths())
        .as("no deletion without a complete listing")
        .contains(path("a.txt"), path("geheim/x.txt"));
  }

  @Test
  void aFolderLargerThanAPageIsReadAcrossPages() throws Exception {
    List<String> names = IntStream.rangeClosed(1, 25).mapToObj(i -> "d" + i + ".txt").toList();
    for (String name : names) {
      samba.put(folder + "/" + name, "Inhalt " + name);
    }
    try (SmbFileStore store = store(4)) {
      FileContainer container = new FileContainer("/" + folder);
      List<String> listed = new ArrayList<>();
      int pages = 0;
      String continuation = null;
      do {
        FilePage page = store.list(container, continuation);
        assertThat(page.entries()).hasSizeLessThanOrEqualTo(4);
        page.entries().forEach(entry -> listed.add(entry.fileName()));
        continuation = page.next();
        pages++;
      } while (continuation != null);

      assertThat(listed).containsExactlyInAnyOrderElementsOf(names);
      assertThat(pages).isGreaterThanOrEqualTo(7);
    }
  }

  @Test
  void namesWithSpecialCharactersKeepTheirPathFromListingToDownload() throws Exception {
    List<String> names =
        List.of(
            "Übersicht Ärzte ß.txt",
            "50 % + mehr.txt",
            "#1 & Co; Version (2).txt",
            "Ordner mit Leerzeichen/Unterordner é/ä.txt");
    for (String name : names) {
      samba.put(folder + "/" + name, "Text von " + name);
    }

    FileSyncHarness.Run run = new FileSyncHarness().fullSync(store(10));

    assertThat(run.ingested())
        .containsExactlyInAnyOrderElementsOf(names.stream().map(this::path).toList());
    try (SmbFileStore store = store(10)) {
      FileContainer container = new FileContainer("/" + folder);
      for (String name : names) {
        FileEntry head = store.head(container, folder + "/" + name);
        assertThat(head.filePath()).isEqualTo(path(name));
        FetchedFile fetched = store.fetch(head, 1024);
        try {
          assertThat(Files.readString(fetched.file(), StandardCharsets.UTF_8))
              .isEqualTo("Text von " + name);
        } finally {
          Files.deleteIfExists(fetched.file());
        }
      }
    }
  }

  @Test
  void aMissingFileOrAFolderIsGoneForASingleLook() throws Exception {
    samba.put(folder + "/unter/a.txt", "A.");
    try (SmbFileStore store = store(10)) {
      FileContainer container = new FileContainer("/" + folder);

      assertThatThrownBy(() -> store.head(container, folder + "/fehlt.txt"))
          .isInstanceOf(FileAccessException.Gone.class);
      assertThatThrownBy(() -> store.head(container, folder + "/unter"))
          .isInstanceOf(FileAccessException.Gone.class);
      assertThatThrownBy(() -> store.head(container, folder + "/fehlt/a.txt"))
          .isInstanceOf(FileAccessException.Gone.class);
    }
  }

  @Test
  void aSpentBudgetEndsTheListingBeforeTheNextMessageIsSent() {
    samba.put(folder + "/a.txt", "A.");
    RequestBudget budget = new RequestBudget(new SourceRequestMeter(), 3, null);
    try (FileStore store =
        SmbTestStores.open(
            samba.settings(samba.credentials(), List.of("/" + folder)), 10, budget)) {
      assertThatThrownBy(() -> store.list(new FileContainer("/" + folder), null))
          .isInstanceOf(RequestBudgetExhaustedException.class);
      assertThat(store.meter().requests()).isEqualTo(3);
    }
  }

  @Test
  void theChangeFeatureFollowsModificationTimeAndSize() throws Exception {
    samba.put(folder + "/a.txt", "Gleich lang.");
    String before = onlyEntry().changeMarker();

    samba.touchEarlier(folder + "/a.txt");
    String touched = onlyEntry().changeMarker();
    samba.put(folder + "/a.txt", "Gleich lang!");
    String rewritten = onlyEntry().changeMarker();

    assertThat(before).startsWith("m:").endsWith("|12");
    assertThat(touched).isNotEqualTo(before);
    assertThat(rewritten).isNotEqualTo(touched);
  }

  private FileEntry onlyEntry() throws Exception {
    try (SmbFileStore store = store(10)) {
      FilePage page = store.list(new FileContainer("/" + folder), null);
      assertThat(page.entries()).hasSize(1);
      return page.entries().getFirst();
    }
  }

  private SmbFileStore store(int pageSize) {
    return SmbTestStores.open(samba.settings(samba.credentials(), List.of("/" + folder)), pageSize);
  }

  private String path(String name) {
    return samba.url(SambaFixture.SHARE) + "/" + folder + "/" + name;
  }
}
