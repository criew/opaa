package io.opaa.indexing.source.smb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.indexing.filesync.Exclusion;
import io.opaa.indexing.filesync.FileAccessException;
import io.opaa.indexing.filesync.FileContainer;
import io.opaa.indexing.filesync.FileEntry;
import io.opaa.indexing.filesync.FilePage;
import io.opaa.sourceaccess.SourceRequestMeter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The walk of {@link SmbFileStore} over a share answered from memory: depth first in name order,
 * and a resumption from a checkpoint that keeps what the walk before it learned - a folder met
 * under another name stays a link, an unreadable folder keeps the container incomplete.
 */
class SmbFileStoreWalkTest {

  private static final FileContainer ROOT = new FileContainer("/");

  /** Folder path below the share to its entries; a missing path cannot be listed. */
  private final Map<String, List<SmbShareClient.Item>> folders = new LinkedHashMap<>();

  private final List<String> listed = new ArrayList<>();

  @Test
  void theWalkGoesDepthFirstInNameOrder() throws Exception {
    folder("", dir("b", 2), file("a.txt"), dir("c", 3));
    folder("b", file("x.txt"), file("y.txt"));
    folder("c", file("z.txt"));

    List<String> names = new ArrayList<>();
    SmbFileStore store = store(10);
    String continuation = null;
    do {
      FilePage page = store.list(ROOT, continuation);
      page.entries().forEach(entry -> names.add(entry.id()));
      continuation = page.next();
    } while (continuation != null);

    assertThat(names).containsExactly("a.txt", "b/x.txt", "b/y.txt", "c/z.txt");
    assertThat(listed).containsExactly("", "b", "c");
  }

  @Test
  void aFolderMetBeforeTheCheckpointIsStillALinkUnderAnotherNameAfterIt() throws Exception {
    folder("", dir("a", 2), dir("m", 3));
    folder("a", file("b.txt"));
    // a link the server resolved itself: another name for "a", the same file id
    folder("m", dir("verweis", 2), file("n.txt"));
    folder("m/verweis", file("b.txt"));

    String checkpoint = checkpointAfter(store(1), "a/b.txt");
    listed.clear();

    List<FileEntry> rest = rest(store(1), checkpoint);

    assertThat(rest).extracting(FileEntry::id).doesNotContain("m/verweis/b.txt");
    assertThat(rest)
        .filteredOn(entry -> entry.exclusion() instanceof Exclusion.NotADocument)
        .extracting(FileEntry::fileName)
        .containsExactly("verweis");
    assertThat(rest).extracting(FileEntry::id).contains("m/n.txt");
    assertThat(listed).as("the link is not entered").doesNotContain("m/verweis");
  }

  @Test
  void anUnreadableFolderBeforeTheCheckpointKeepsTheContainerIncompleteAfterIt() throws Exception {
    folder("", file("a.txt"), dir("geheim", 2), dir("offen", 3));
    folder("offen", file("b.txt"), file("c.txt"));

    SmbFileStore first = store(1);
    FilePage page = first.list(ROOT, null);
    assertThat(page.entries()).extracting(FileEntry::id).containsExactly("a.txt");
    page = first.list(ROOT, page.next());
    assertThat(page.entries()).as("the unreadable folder only noted").isEmpty();
    String checkpoint = page.checkpoint();

    SmbFileStore second = store(1);
    FilePage resumed = second.resume(ROOT, checkpoint);
    List<String> ids = new ArrayList<>();
    resumed.entries().forEach(entry -> ids.add(entry.id()));
    String continuation = resumed.next();

    assertThatThrownBy(
            () -> {
              String next = continuation;
              while (next != null) {
                FilePage more = second.list(ROOT, next);
                more.entries().forEach(entry -> ids.add(entry.id()));
                next = more.next();
              }
            })
        .isInstanceOf(FileAccessException.ContainerUnlistable.class)
        .hasMessageContaining("„/geheim“");
    assertThat(ids).contains("offen/c.txt");
  }

  @Test
  void aFolderReplacedUnderItsNameOnTheWayToTheCheckpointIsWalkedOn() throws Exception {
    folder("", dir("m", 3), file("z.txt"));
    folder("m", file("a.txt"), file("b.txt"), file("c.txt"));
    String checkpoint = checkpointAfter(store(1), "m/a.txt");
    // the same name, a folder with another file id the walk has not met
    folder("", dir("m", 9), file("z.txt"));

    List<FileEntry> rest = rest(store(1), checkpoint);

    assertThat(rest).extracting(FileEntry::id).containsExactly("m/b.txt", "m/c.txt", "z.txt");
  }

  @Test
  void aCheckpointFromAnotherVersionHasExpired() {
    folder("", file("a.txt"));

    assertThatThrownBy(() -> store(1).resume(ROOT, "nc1:[]"))
        .isInstanceOf(FileAccessException.CheckpointExpired.class);
  }

  /** The checkpoint of the page that delivered {@code id}. */
  private static String checkpointAfter(SmbFileStore store, String id) throws Exception {
    FilePage page = store.list(ROOT, null);
    while (page.entries().stream().noneMatch(entry -> entry.id().equals(id))) {
      page = store.list(ROOT, page.next());
    }
    return page.checkpoint();
  }

  private List<FileEntry> rest(SmbFileStore store, String checkpoint) throws Exception {
    List<FileEntry> entries = new ArrayList<>();
    FilePage page = store.resume(ROOT, checkpoint);
    entries.addAll(page.entries());
    while (page.next() != null) {
      page = store.list(ROOT, page.next());
      entries.addAll(page.entries());
    }
    return entries;
  }

  private void folder(String path, SmbShareClient.Item... items) {
    folders.put(path, List.of(items));
  }

  private static SmbShareClient.Item dir(String name, long fileId) {
    return new SmbShareClient.Item(name, true, 0, 1, 0x10, 0, fileId);
  }

  private static SmbShareClient.Item file(String name) {
    return new SmbShareClient.Item(name, false, 5, 1, 0x20, 0, 0);
  }

  private SmbFileStore store(int pageSize) throws Exception {
    SmbShareClient client = mock(SmbShareClient.class);
    when(client.meter()).thenReturn(new SourceRequestMeter());
    when(client.list(anyString()))
        .thenAnswer(
            invocation -> {
              String path = invocation.getArgument(0);
              listed.add(path);
              List<SmbShareClient.Item> items = folders.get(path);
              if (items == null) {
                throw new SmbAccessException.AccessDenied("Zugriff auf „" + path + "“ verweigert.");
              }
              return listing(items, path.isEmpty() ? 1 : 0);
            });
    return new SmbFileStore(
        client, SmbAddress.parse("smb://server/freigabe"), SmbSourceSettings.WHOLE_SHARE, pageSize);
  }

  private static SmbShareClient.Listing listing(List<SmbShareClient.Item> items, long folderId)
      throws Exception {
    SmbShareClient.Listing listing = mock(SmbShareClient.Listing.class);
    Iterator<SmbShareClient.Item> iterator = items.iterator();
    when(listing.hasNext()).thenAnswer(invocation -> iterator.hasNext());
    when(listing.next()).thenAnswer(invocation -> iterator.next());
    when(listing.folderId()).thenReturn(folderId);
    return listing;
  }
}
