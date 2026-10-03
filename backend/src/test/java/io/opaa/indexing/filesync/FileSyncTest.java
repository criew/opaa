package io.opaa.indexing.filesync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingRunEvent;
import io.opaa.indexing.source.SourceSyncState;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
  void anUnchangedFolderIsNotListedAndKeepsItsDocumentsAndTheirFolders() throws Exception {
    InMemoryFileStore store =
        new InMemoryFileStore()
            .withFolderMarkers()
            .container("A")
            .put("A", "alt/x.txt", "X.")
            .put("A", "alt/tief/y.txt", "Y.")
            .put("A", "altlasten/z.txt", "Z.")
            .put("A", "neu.txt", "Neu.");
    FileSyncHarness.Run first = harness.fullSync(store);
    assertThat(first.ingested()).hasSize(4);
    UUID altFolder = UUID.randomUUID();
    harness
        .stored(InMemoryFileStore.filePath("A", "alt/x.txt"))
        .orElseThrow()
        .setFolderId(altFolder);

    store.reset().remove("A", "altlasten/z.txt");
    FileSyncHarness.Run run = harness.fullSync(store);

    assertThat(store.recalled().get("A")).containsKeys("", "alt", "alt / tief", "altlasten");
    assertThat(run.listingComplete()).isTrue();
    assertThat(run.eventsOf(IndexingEventCategory.REMOVED))
        .as("the folder ends at its name: a sibling sharing its name prefix is not kept")
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(InMemoryFileStore.filePath("A", "altlasten/z.txt"));
    assertThat(harness.storedPaths())
        .containsExactlyInAnyOrder(
            InMemoryFileStore.filePath("A", "alt/x.txt"),
            InMemoryFileStore.filePath("A", "alt/tief/y.txt"),
            InMemoryFileStore.filePath("A", "neu.txt"));
    assertThat(run.skipped())
        .as("only the root's own file is visited, nothing below the unchanged folder")
        .isEqualTo(1);
    verify(harness.folderService())
        .pruneOrphanedFolders(eq(harness.library()), eq(Set.of(altFolder)));
    assertThat(harness.state().subtreeMemory().containers().get("A"))
        .as("the unchanged folders carry their markers over")
        .containsKeys("", "alt", "alt / tief")
        .doesNotContainKey("altlasten");
  }

  @Test
  void anUnchangedContainerKeepsItsWholeBestandWithoutVisitingAFile() throws Exception {
    InMemoryFileStore store =
        new InMemoryFileStore()
            .withFolderMarkers()
            .container("A")
            .put("A", "a.txt", "A.")
            .put("A", "ordner/b.txt", "B.");
    harness.fullSync(store);

    FileSyncHarness.Run run = harness.fullSync(store.reset());

    assertThat(run.listingComplete()).isTrue();
    assertThat(run.skipped() + run.processed()).isZero();
    assertThat(run.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
    assertThat(harness.storedPaths()).hasSize(2);
  }

  @Test
  void aFolderWithAnUnsettledEntryIsListedAgainNextTime() throws Exception {
    InMemoryFileStore store =
        new InMemoryFileStore()
            .withFolderMarkers()
            .container("A")
            .put("A", "gut/a.txt", "A.")
            .put("A", "kaputt/tief/b.txt", "B.");
    String broken = InMemoryFileStore.filePath("A", "kaputt/tief/b.txt");
    harness.failIngestOf(broken);
    harness.fullSync(store);

    assertThat(harness.state().subtreeMemory().containers().get("A"))
        .as("neither the failed entry's folders nor the root are remembered")
        .containsOnlyKeys("gut");

    harness.healIngests();
    FileSyncHarness.Run run = harness.fullSync(store.reset());

    assertThat(run.ingested()).containsExactly(broken);
    assertThat(harness.state().subtreeMemory().containers().get("A"))
        .containsOnlyKeys("", "gut", "kaputt", "kaputt / tief");
  }

  @Test
  void theMemoryExpiresAfterItsMaximumAge() throws Exception {
    InMemoryFileStore store =
        new InMemoryFileStore().withFolderMarkers().container("A").put("A", "a/x.txt", "X.");
    harness.subtreeMemoryMaxAge(Duration.ofDays(7));
    harness.fullSync(store);
    harness.advanceClock(Duration.ofDays(6)).fullSync(store.reset());
    assertThat(store.recalled().get("A")).isNotEmpty();

    harness.advanceClock(Duration.ofDays(2)).fullSync(store.reset());

    assertThat(store.recalled().get("A"))
        .as("measured from the full listing that established the memory, not from the last run")
        .isEmpty();
    harness.fullSync(store.reset());
    assertThat(store.recalled().get("A")).isNotEmpty();
  }

  @Test
  void aMemoryJudgedUnderAnotherBasisIsNotRecalled() throws Exception {
    InMemoryFileStore store =
        new InMemoryFileStore().withFolderMarkers().container("A").put("A", "a/x.txt", "X.");
    harness.fullSync(store);
    SourceSyncState.SubtreeMemory memory = harness.state().subtreeMemory();
    harness
        .state()
        .rememberSubtrees(
            new SourceSyncState.SubtreeMemory(
                "v1|1|txt", memory.establishedAt(), memory.containers(), memory.documentCounts()));

    harness.fullSync(store.reset());

    assertThat(store.recalled().get("A")).isEmpty();
  }

  @Test
  void aStoreThatReportsNoFoldersLeavesNoMemory() throws Exception {
    InMemoryFileStore store = new InMemoryFileStore().container("A").put("A", "a/x.txt", "X.");

    harness.fullSync(store);

    assertThat(harness.state().subtreeMemory()).isEqualTo(SourceSyncState.SubtreeMemory.NONE);
  }

  @Test
  void aFolderReportedUnchangedWithoutAHandedOverMarkerKeepsTheBestandButReconcilesNothing()
      throws Exception {
    InMemoryFileStore store =
        new InMemoryFileStore().withFolderMarkers().container("A").put("A", "a/x.txt", "X.");
    harness.fullSync(store);
    store.put("A", "a/y.txt", "Y.");

    FileStore lying =
        new FileStore() {
          @Override
          public List<FileContainer> containers() {
            return store.containers();
          }

          @Override
          public FilePage list(FileContainer container, String continuation) {
            return new FilePage(List.of(), null, List.of("nie übergeben"), Map.of("", "m:1"));
          }

          @Override
          public FileEntry head(FileContainer container, String id) {
            throw new UnsupportedOperationException();
          }

          @Override
          public FetchedFile fetch(FileEntry entry, long maxBytes) {
            throw new UnsupportedOperationException();
          }

          @Override
          public io.opaa.sourceaccess.SourceRequestMeter meter() {
            return store.meter();
          }

          @Override
          public void close() {}
        };
    FileSyncHarness.Run run = harness.fullSync(lying);

    assertThat(run.listingComplete()).isFalse();
    assertThat(run.unlistedContainerKeys()).containsExactly("A");
    assertThat(harness.storedPaths()).containsExactly(InMemoryFileStore.filePath("A", "a/x.txt"));
  }

  @Test
  void aNewFileThatIsNotAvailableAndAPathTooLongForTheRowKeepTheirFoldersOutOfTheMemory()
      throws Exception {
    String longFolder = "x".repeat(2100);
    InMemoryFileStore store =
        new InMemoryFileStore()
            .withFolderMarkers()
            .container("A")
            .put("A", "gut/a.txt", "A.")
            .put("A", longFolder + "/b.txt", "B.");

    harness.fullSync(store);

    assertThat(harness.state().subtreeMemory().containers().get("A"))
        .as("the cut path keeps its folder and the root out")
        .containsOnlyKeys("gut");
  }

  @Test
  void aFolderCoversItselfAndWhatLiesBelowItButNoSiblingSharingItsNamePrefix() {
    assertThat(FileSync.covers("alt", "alt")).isTrue();
    assertThat(FileSync.covers("alt", "alt / tief")).isTrue();
    assertThat(FileSync.covers("alt", "altlasten")).isFalse();
    assertThat(FileSync.covers("", "alt / tief")).isTrue();
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
