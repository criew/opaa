package io.opaa.indexing.filesync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingRunEvent;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What {@link FileSync} offers a store beyond the contract (ADR-0040, Entscheidungen 1 and 6): the
 * start cursors of a change log, held from the first begin of a full sync until it completes, and
 * subtrees a store reports unchanged instead of listing them.
 */
class FileSyncTest {

  private final FileSyncHarness harness;

  FileSyncTest() throws Exception {
    harness = new FileSyncHarness();
  }

  @Test
  void startCursorsAreHeldBeforeTheListingKeptOnResumptionAndValidOnlyOnceTheSyncCompletes()
      throws Exception {
    InMemoryFileStore store =
        new InMemoryFileStore()
            .container("A")
            .container("B")
            .withChangeFeed()
            .put("A", "a.txt", "Text A.")
            .put("B", "b.txt", "Text B.")
            .denyListing("B");

    harness.fullSync(store);

    assertThat(store.calls())
        .as("one start cursor per stream, before anything is listed")
        .startsWith("startCursor stream:A", "startCursor stream:B", "list A");
    assertThat(harness.state().pendingChangeCursors())
        .containsOnly(
            java.util.Map.entry("stream:A", "cursor-1"),
            java.util.Map.entry("stream:B", "cursor-2"));
    assertThat(harness.state().changeCursors()).as("an incomplete sync validates none").isEmpty();

    InMemoryFileStore resumed = store.allowListing("B");
    harness.fullSync(resumed.reset());

    assertThat(resumed.calls())
        .as("the resumed sync keeps the cursors its first begin held")
        .noneMatch(call -> call.startsWith("startCursor"));
    assertThat(harness.state().changeCursors())
        .containsOnly(
            java.util.Map.entry("stream:A", "cursor-1"),
            java.util.Map.entry("stream:B", "cursor-2"));
    assertThat(harness.state().pendingChangeCursors()).isEmpty();

    harness.fullSync(resumed.reset());

    assertThat(resumed.calls()).startsWith("startCursor stream:A", "startCursor stream:B");
    assertThat(harness.state().changeCursors())
        .as("a new full sync replaces them once it completes")
        .containsOnly(
            java.util.Map.entry("stream:A", "cursor-3"),
            java.util.Map.entry("stream:B", "cursor-4"));
  }

  @Test
  void aStoreWithoutAChangeLogHoldsNoCursors() throws Exception {
    InMemoryFileStore store = new InMemoryFileStore().container("A").put("A", "a.txt", "Text.");

    harness.fullSync(store);

    assertThat(store.calls()).noneMatch(call -> call.startsWith("startCursor"));
    assertThat(harness.state().changeCursors()).isEmpty();
    assertThat(harness.state().pendingChangeCursors()).isEmpty();
  }

  @Test
  void anUnchangedSubtreeKeepsItsDocumentsAndTheirFoldersWithoutListingThem() throws Exception {
    InMemoryFileStore store =
        new InMemoryFileStore()
            .container("A")
            .put("A", "alt/x.txt", "X.")
            .put("A", "alt/tief/y.txt", "Y.")
            .put("A", "altlasten/z.txt", "Z.")
            .put("A", "neu.txt", "Neu.");
    harness.fullSync(store);
    UUID altFolder = UUID.randomUUID();
    harness
        .stored(InMemoryFileStore.filePath("A", "alt/x.txt"))
        .orElseThrow()
        .setFolderId(altFolder);

    store.reset().unchangedSubtree("A", "alt").remove("A", "altlasten/z.txt");
    FileSyncHarness.Run run = harness.fullSync(store);

    assertThat(run.listingComplete()).isTrue();
    assertThat(run.eventsOf(IndexingEventCategory.REMOVED))
        .as("the subtree ends at its path boundary: a sibling sharing its name prefix is not kept")
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(InMemoryFileStore.filePath("A", "altlasten/z.txt"));
    assertThat(harness.storedPaths())
        .containsExactlyInAnyOrder(
            InMemoryFileStore.filePath("A", "alt/x.txt"),
            InMemoryFileStore.filePath("A", "alt/tief/y.txt"),
            InMemoryFileStore.filePath("A", "neu.txt"));
    assertThat(store.calls()).noneMatch(call -> call.contains("alt/"));
    verify(harness.folderService())
        .pruneOrphanedFolders(eq(harness.library()), eq(Set.of(altFolder)));
  }

  @Test
  void anUnchangedSubtreeMustEndAtAPathBoundary() {
    assertThatThrownBy(() -> new FilePage(List.of(), null, List.of("mem://A/alt")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anEventRunSkipsADeselectedFileWithoutFetchingItOrKeepingItPresent() throws Exception {
    InMemoryFileStore store =
        new InMemoryFileStore()
            .container("A")
            .put("A", "entwurf.txt", "Entwurf.")
            .deselect("entwurf.txt");

    FileSyncHarness.Run run =
        harness.refresh(
            store,
            List.of(
                new FileReference(
                    new FileContainer("A"),
                    "entwurf.txt",
                    InMemoryFileStore.filePath("A", "entwurf.txt"))));

    assertThat(run.ingested()).isEmpty();
    assertThat(run.skipped()).isEqualTo(1);
    assertThat(store.calls()).containsExactly("head A/entwurf.txt");
  }

  @Test
  void anEventRunRemovesAConfirmedDeletionAndNeverReconciles() throws Exception {
    InMemoryFileStore store =
        new InMemoryFileStore()
            .container("A")
            .put("A", "bleibt.txt", "B.")
            .put("A", "weg.txt", "W.");
    harness.fullSync(store);
    store.remove("A", "weg.txt");

    FileSyncHarness.Run run =
        harness.refresh(
            store,
            List.of(
                new FileReference(
                    new FileContainer("A"),
                    "weg.txt",
                    InMemoryFileStore.filePath("A", "weg.txt"))));

    assertThat(run.eventsOf(IndexingEventCategory.REMOVED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(InMemoryFileStore.filePath("A", "weg.txt"));
    assertThat(run.listingComplete()).isNull();
    assertThat(harness.storedPaths())
        .containsExactly(InMemoryFileStore.filePath("A", "bleibt.txt"));
  }

  @Test
  void aFetchInAnotherFormatIsIngestedUnderItsOwnNameWithItsNote() throws Exception {
    InMemoryFileStore store =
        new InMemoryFileStore()
            .container("A")
            .put("A", "protokoll.docx", "Ein Text.")
            .exportAsText("protokoll.docx");

    FileSyncHarness.Run run = harness.fullSync(store);

    String path = InMemoryFileStore.filePath("A", "protokoll.docx");
    assertThat(run.ingested()).containsExactly(path);
    assertThat(harness.stored(path).orElseThrow().getFileName()).isEqualTo("protokoll.txt");
    assertThat(run.eventsOf(IndexingEventCategory.FORMAT_MISMATCH))
        .extracting(IndexingRunEvent::getMessage, IndexingRunEvent::getReference)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("Als Text exportiert.", path));
  }
}
