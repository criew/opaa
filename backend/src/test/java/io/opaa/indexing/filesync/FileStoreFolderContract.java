package io.opaa.indexing.filesync;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingRunEvent;
import org.junit.jupiter.api.Test;

/**
 * The contract of a store that reports folders and skips unchanged ones ({@link FileStore#recall}):
 * on top of {@link FileStoreContract}, a change or deletion deep down reaches the run, a sibling
 * sharing a name prefix is not mistaken for the folder, a renamed folder keeps its bestand over the
 * following runs, and a document marked for a run or removed by hand, also while a run is under way,
 * is visited again although its folder did not change. Every file connector whose store reports
 * folders extends this instead.
 */
public abstract class FileStoreFolderContract extends FileStoreContract {

  @Test
  void aChangeDeepDownReachesTheRunAndAnUnchangedTreeIsLeftAlone() throws Exception {
    fixture.put(0, "a/b/c/tief.txt", "Erste Fassung.");
    fixture.put(0, "a/flach.txt", "Flach.");
    fixture.put(0, "oben.txt", "Oben.");
    fullSync();

    fixture.put(0, "a/b/c/tief.txt", "Zweite, längere Fassung.");
    FileSyncHarness.Run changed = fullSync();
    FileSyncHarness.Run unchanged = fullSync();

    assertThat(changed.ingested())
        .as("the change three levels down reaches the root's marker")
        .containsExactly(fixture.filePath(0, "a/b/c/tief.txt"));
    assertThat(unchanged.ingested()).isEmpty();
    assertThat(unchanged.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
    assertThat(unchanged.listingComplete()).isTrue();
    assertThat(harness.storedPaths()).hasSize(3);
  }

  @Test
  void aDeletionDeepDownIsRemovedAndASiblingSharingTheNamePrefixStays() throws Exception {
    fixture.put(0, "alt/tief/x.txt", "X.");
    fixture.put(0, "alt/y.txt", "Y.");
    fixture.put(0, "altlasten/z.txt", "Z.");
    fullSync();

    fixture.remove(0, "alt/tief/x.txt");
    FileSyncHarness.Run run = fullSync();
    FileSyncHarness.Run after = fullSync();

    assertThat(run.eventsOf(IndexingEventCategory.REMOVED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(fixture.filePath(0, "alt/tief/x.txt"));
    assertThat(after.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
    assertThat(harness.storedPaths())
        .containsExactlyInAnyOrder(
            fixture.filePath(0, "alt/y.txt"), fixture.filePath(0, "altlasten/z.txt"));
  }

  @Test
  void aRenamedFolderKeepsItsBestandOverTheFollowingRuns() throws Exception {
    fixture.put(0, "alt/x.txt", "X.");
    fixture.put(0, "alt/tief/y.txt", "Y.");
    fixture.put(0, "oben.txt", "Oben.");
    fixture.put(1, "anderswo.txt", "A.");
    fullSync();

    fixture.move(0, "alt", "neu");
    fullSync();
    // the root changes, the renamed folder does not: it is reported unchanged under its new path
    fixture.put(0, "oben.txt", "Oben, zweite Fassung.");
    FileSyncHarness.Run second = fullSync();
    FileSyncHarness.Run third = fullSync();

    assertThat(harness.storedPaths())
        .containsExactlyInAnyOrder(
            fixture.filePath(0, "neu/x.txt"),
            fixture.filePath(0, "neu/tief/y.txt"),
            fixture.filePath(0, "oben.txt"),
            fixture.filePath(1, "anderswo.txt"));
    assertThat(second.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
    assertThat(third.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
  }

  @Test
  void aDocumentMarkedForTheNextRunIsFetchedAlthoughItsFolderDidNotChange() throws Exception {
    fixture.put(0, "akten/2026/x.txt", "X.");
    fixture.put(0, "akten/2026/y.txt", "Y.");
    fullSync();
    fullSync();

    harness.markForReindex(fixture.filePath(0, "akten/2026/x.txt"));
    FileSyncHarness.Run run = fullSync();

    assertThat(run.ingested()).containsExactly(fixture.filePath(0, "akten/2026/x.txt"));
  }

  @Test
  void aDocumentRemovedOutsideARunReturnsWithTheNextRun() throws Exception {
    fixture.put(0, "akten/x.txt", "X.");
    fixture.put(0, "akten/y.txt", "Y.");
    fullSync();

    harness.deleteStored(fixture.filePath(0, "akten/x.txt"));
    FileSyncHarness.Run run = fullSync();

    assertThat(run.ingested()).containsExactly(fixture.filePath(0, "akten/x.txt"));
    assertThat(harness.storedPaths()).hasSize(2);
  }

  @Test
  void aDocumentRemovedDuringARunReturnsWithTheNextRun() throws Exception {
    fixture.put(0, "akten/x.txt", "X.");
    fixture.put(0, "akten/y.txt", "Y.");
    fixture.put(1, "anderswo.txt", "A.");
    fullSync();

    String removed = fixture.filePath(0, "akten/x.txt");
    harness.fullSync(
        new BeforeListingStore(
            fixture.open(PAGE_SIZE), fixture.containerKey(1), () -> harness.deleteStored(removed)));
    FileSyncHarness.Run run = fullSync();

    assertThat(run.ingested()).containsExactly(removed);
    assertThat(harness.storedPaths()).hasSize(3);
  }
}
