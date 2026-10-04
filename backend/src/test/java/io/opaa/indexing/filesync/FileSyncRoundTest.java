package io.opaa.indexing.filesync;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingRunEvent;
import io.opaa.indexing.source.SourceSyncState;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What {@link FileSync} does with a full sync that spans several runs, beyond the contract
 * (ADR-0040, Nachtrag Abgleichsrunde): what an event run confirms and what a failed run leaves, the
 * order after an expired checkpoint, the entry bound of a round, the stall note, and the revisits
 * of a round.
 */
class FileSyncRoundTest {

  private final FileSyncHarness harness;

  FileSyncRoundTest() throws Exception {
    harness = new FileSyncHarness();
  }

  private FileSyncHarness.Run run(InMemoryFileStore store, int budget) {
    return harness.fullSync(store.reset().budget(budget));
  }

  private static String checkpointOf(SourceSyncState state, String container) {
    return state.scanProgress().containers().get(container).checkpoint();
  }

  // regression guard for #2202: a file an event run confirmed in a finished container stays
  @Test
  void anEventRunBetweenTwoRunsOfARoundKeepsItsDocument() {
    InMemoryFileStore store =
        new InMemoryFileStore()
            .withCheckpoints()
            .absenceProof(AbsenceProof.LOCATION_IDENTITY)
            .pageSize(2)
            .container("A")
            .container("B")
            .put("A", "a1.txt", "A1.")
            .put("A", "a2.txt", "A2.")
            .put("B", "b1.txt", "B1.")
            .put("B", "b2.txt", "B2.")
            .put("B", "b3.txt", "B3.");
    run(store, 6);
    assertThat(harness.state().completedScopeKeys()).containsExactly("A");
    assertThat(harness.state().isFullSyncInterrupted()).isTrue();

    store.put("A", "neu.txt", "Neu.");
    String added = InMemoryFileStore.filePath("A", "neu.txt");
    harness.refresh(
        store.reset().budget(0),
        List.of(new FileReference(new FileContainer("A"), "neu.txt", added)));
    FileSyncHarness.Run completing = run(store, 0);

    assertThat(completing.listingComplete()).isTrue();
    assertThat(completing.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
    assertThat(harness.storedPaths()).contains(added).hasSize(6);
    assertThat(harness.state().isFullSyncInterrupted()).isFalse();
  }

  @Test
  void aRunThatFailsBetweenTwoRunsRemovesNothingAndKeepsTheProgress() {
    InMemoryFileStore store =
        new InMemoryFileStore()
            .withCheckpoints()
            .absenceProof(AbsenceProof.LOCATION_IDENTITY)
            .pageSize(2)
            .container("A");
    for (int i = 1; i <= 6; i++) {
      store.put("A", "d" + i + ".txt", "Erste Fassung " + i);
    }
    run(store, 0);
    for (int i = 1; i <= 6; i++) {
      store.put("A", "d" + i + ".txt", "Zweite, längere Fassung " + i);
    }
    run(store, 4);
    String kept = checkpointOf(harness.state(), "A");
    assertThat(kept).isNotNull();

    store.remove("A", "d6.txt");
    store.rejectCredentials();
    FileSyncHarness.Run failed = run(store, 0);

    assertThat(failed.failure()).isNotBlank();
    assertThat(failed.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
    assertThat(harness.storedPaths()).hasSize(6);
    assertThat(checkpointOf(harness.state(), "A")).as("the progress stays").isEqualTo(kept);

    store.acceptCredentials();
    FileSyncHarness.Run resumed = run(store, 0);

    assertThat(store.calls()).startsWith("resume A @" + kept);
    assertThat(resumed.listingComplete()).isTrue();
    assertThat(resumed.eventsOf(IndexingEventCategory.REMOVED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(InMemoryFileStore.filePath("A", "d6.txt"));
  }

  @Test
  void anExpiredCheckpointGoesBehindTheOtherContainersAndTwiceInARowOutOfTheRun() {
    InMemoryFileStore store =
        new InMemoryFileStore()
            .withCheckpoints()
            .pageSize(2)
            .container("A")
            .container("B")
            .put("B", "b1.txt", "B1.")
            .put("B", "b2.txt", "B2.");
    for (int i = 1; i <= 6; i++) {
      store.put("A", "a" + i + ".txt", "A" + i + ".");
    }
    run(store, 4);
    assertThat(checkpointOf(harness.state(), "A")).isNotNull();

    store.expireCheckpoints();
    FileSyncHarness.Run restarted = run(store, 8);

    assertThat(store.calls())
        .as("the expired container starts over behind the others")
        .containsSubsequence("list B", "list A");
    assertThat(store.calls().getFirst()).startsWith("resume A");
    assertThat(restarted.eventsOf(IndexingEventCategory.REJECTED))
        .extracting(IndexingRunEvent::getMessage)
        .anySatisfy(message -> assertThat(message).endsWith(FileSync.RESTART_SUFFIX));
    assertThat(checkpointOf(harness.state(), "A")).isNotNull();

    store.expireCheckpoints();
    FileSyncHarness.Run skipped = run(store, 0);

    assertThat(store.calls()).as("expired twice in a row: not in this run").hasSize(1);
    assertThat(skipped.listingComplete()).isFalse();
    assertThat(skipped.unlistedContainerKeys()).containsExactly("A");
    assertThat(skipped.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();

    FileSyncHarness.Run next = run(store, 0);

    assertThat(next.failure()).isNull();
    assertThat(harness.state().isFullSyncInterrupted()).isFalse();
    assertThat(harness.storedPaths()).hasSize(8);
  }

  @Test
  void theEntryBoundCountsTheWholeRoundNotOneRun() {
    harness.maxEntriesPerRun(5);
    InMemoryFileStore store = new InMemoryFileStore().withCheckpoints().pageSize(2).container("A");
    for (int i = 1; i <= 8; i++) {
      store.put("A", "d" + i + ".txt", "Text " + i);
    }
    FileSyncHarness.Run first = run(store, 6);
    assertThat(first.failure()).isNull();

    FileSyncHarness.Run second = run(store, 0);

    assertThat(second.failure()).isEqualTo("Mehr als 5 Einträge.");
  }

  @Test
  void aRunThatOnlyMovesTheCheckpointOnIsNoStallButOneThatMovesNothingIs() {
    InMemoryFileStore store = new InMemoryFileStore().withCheckpoints().pageSize(2).container("A");
    for (int i = 1; i <= 6; i++) {
      store.put("A", "d" + i + ".txt", "Text " + i);
    }
    run(store, 0);

    FileSyncHarness.Run listingOnly = run(store, 2);

    assertThat(listingOnly.ingested()).isEmpty();
    assertThat(listingOnly.eventsOf(IndexingEventCategory.BUDGET_EXHAUSTED)).hasSize(1);
    assertThat(listingOnly.eventsOf(IndexingEventCategory.ERROR))
        .as("the checkpoint moved on")
        .isEmpty();

    store.put("A", "d5.txt", "Text 5, jetzt länger");
    store.put("A", "d6.txt", "Text 6, jetzt länger");
    FileSyncHarness.Run stalled = run(store, 1);

    assertThat(stalled.eventsOf(IndexingEventCategory.ERROR))
        .extracting(IndexingRunEvent::getMessage)
        .anySatisfy(message -> assertThat(message).contains("Kein Eintrag neu aufgenommen."));
  }

  @Test
  void aDocumentDeletedInAContainerTheRoundFinishedKeepsItsFolderOutOfTheMemory() {
    InMemoryFileStore store =
        new InMemoryFileStore()
            .withCheckpoints()
            .withFolderMarkers()
            .withStableIds()
            .pageSize(2)
            .container("A")
            .container("B")
            .put("A", "akten/x.txt", "X.")
            .put("A", "akten/y.txt", "Y.");
    for (int i = 1; i <= 4; i++) {
      store.put("B", "b" + i + "/datei.txt", "B" + i + ".");
    }
    run(store, 0);
    store.put("A", "akten/y.txt", "Y, zweite Fassung.");
    for (int i = 1; i <= 4; i++) {
      store.put("B", "b" + i + "/datei.txt", "B" + i + ", zweite Fassung.");
    }
    run(store, 6);
    assertThat(harness.state().completedScopeKeys()).containsExactly("A");

    harness.deleteStored(store.filePathOf("A", "akten/x.txt"));
    run(store, 0);

    assertThat(harness.state().isFullSyncInterrupted()).isFalse();
    assertThat(harness.state().subtreeMemory().containers().getOrDefault("A", java.util.Map.of()))
        .as("no folder above the deletion is remembered")
        .doesNotContainKeys("", "akten");
    assertThat(harness.revisits())
        .as("not seen at the start of A: kept for the next round")
        .hasSize(1);

    FileSyncHarness.Run next = run(store, 0);

    assertThat(next.ingested()).containsExactly(store.filePathOf("A", "akten/x.txt"));
    assertThat(harness.revisits()).isEmpty();
  }
}
