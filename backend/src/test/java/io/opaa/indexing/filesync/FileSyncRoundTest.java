package io.opaa.indexing.filesync;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.document.DocumentIngestResult;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingRunEvent;
import io.opaa.indexing.job.RequestBudgetExhaustedException;
import io.opaa.indexing.source.SourceSyncState;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

  @Test
  void markersARoundJudgedUnderAnotherSizeBoundAreNotRemembered() {
    InMemoryFileStore store =
        new InMemoryFileStore()
            .withCheckpoints()
            .withFolderMarkers()
            .withStableIds()
            .pageSize(2)
            .container("A");
    for (int i = 1; i <= 4; i++) {
      store.put("A", "o" + i + "/datei.txt", "Datei " + i);
    }
    run(store, 4);
    assertThat(harness.state().isFullSyncInterrupted()).isTrue();

    harness.maxFileSize(FileSyncHarness.MAX_FILE_SIZE * 2);
    run(store, 0);

    assertThat(harness.state().isFullSyncInterrupted()).isFalse();
    assertThat(harness.state().subtreeMemory().containers().getOrDefault("A", java.util.Map.of()))
        .as("the folders listed under the former bound are listed again")
        .doesNotContainKeys("", "o1", "o2");
  }

  // regression guard for #2202: a resumed page that names no folder still checks the place of a
  // file
  @Test
  void aFileMovedBeforeTheRoundIsPlacedAlsoOnAResumedPageThatNamesNoFolder() {
    InMemoryFileStore store =
        new InMemoryFileStore()
            .withCheckpoints()
            .withFolderMarkers()
            .withStableIds()
            .pageSize(2)
            .container("A")
            .put("A", "a/x/f10.txt", "Wandert.")
            .put("A", "c/z/w/f1.txt", "Eins.")
            .put("A", "c/z/w/f2.txt", "Zwei.")
            .put("A", "c/z/w/f3.txt", "Drei.")
            .put("A", "f0.txt", "Null.");
    run(store, 0);
    String moved = store.filePathOf("A", "a/x/f10.txt");
    store.move("A", "a/x/f10.txt", "c/z/w/f4.txt");
    // a revisit at the root hands over no marker, so the resumed page names no folder at all
    harness.deleteStored(store.filePathOf("A", "f0.txt"));
    run(store, 1);
    run(store, 0);
    assertThat(harness.stored(moved).orElseThrow().getSourceHierarchyPath())
        .as("the row follows its file")
        .isEqualTo("c / z / w");

    store.put("A", "f9.txt", "Neu im Stamm.");
    FileSyncHarness.Run next = run(store, 0);

    assertThat(next.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
    assertThat(harness.stored(moved)).isPresent();
  }

  // regression guard for #2202: a folder that changed during the round leaves no marker behind
  @Test
  void aFolderTakenAsUnchangedAndListedLaterInTheRoundIsNotRemembered() {
    InMemoryFileStore store =
        new InMemoryFileStore()
            .withCheckpoints()
            .withFolderMarkers()
            .withStableIds()
            .pageSize(1)
            .container("A")
            .put("A", "a/f1.txt", "Eins.")
            .put("A", "a/f2.txt", "Zwei.")
            .put("A", "c/f3.txt", "Drei.")
            .put("A", "c/f4.txt", "Vier.");
    run(store, 0);
    String gone = store.filePathOf("A", "c/f4.txt");
    store.put("A", "a/f1.txt", "Eins, zweite Fassung.");
    run(store, 2);
    assertThat(harness.state().isFullSyncInterrupted()).isTrue();

    store.remove("A", "c/f4.txt");
    run(store, 0);
    assertThat(harness.state().isFullSyncInterrupted()).isFalse();
    FileSyncHarness.Run next = run(store, 0);

    assertThat(next.eventsOf(IndexingEventCategory.REMOVED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(gone);
  }

  // regression guard for #2202: a file met on a page past the checkpoint is not taken as seen
  @Test
  void aFileMetOnlyOnAPagePastTheCheckpointDoesNotCountAsSeen() {
    InMemoryFileStore store =
        new InMemoryFileStore()
            .withCheckpoints()
            .withFolderMarkers()
            .withStableIds()
            .pageSize(1)
            .container("A")
            .put("A", "g/k.txt", "Bleibt.")
            .put("A", "g/x.txt", "Wandert.");
    run(store, 0);
    String moved = store.filePathOf("A", "g/x.txt");
    store.move("A", "g/x.txt", "h/x.txt");
    // the page with the moved file is listed, its download refused by the budget
    run(store, 2);
    assertThat(harness.state().isFullSyncInterrupted()).isTrue();

    store.remove("A", "h/x.txt");
    run(store, 0);
    assertThat(harness.state().isFullSyncInterrupted()).isFalse();
    FileSyncHarness.Run next = run(store, 0);

    assertThat(next.eventsOf(IndexingEventCategory.REMOVED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(moved);
  }

  // regression guard for #2202: a row left at its old place keeps no folder there remembered
  @Test
  void aRowLeftAtItsOldPlaceKeepsThatFolderOutOfTheMemory() {
    InMemoryFileStore store =
        new InMemoryFileStore()
            .withFolderMarkers()
            .withStableIds()
            .container("A")
            .put("A", "c/z/w/f4.txt", "Vier.");
    run(store, 0);
    String stale = store.filePathOf("A", "c/z/w/f4.txt");
    // the folder moves away and another takes its name; the moved file fails to be taken up
    store.move("A", "c", "n").put("A", "c/z/w/g.txt", "Neu.");
    harness.failIngestOf(stale);
    run(store, 0);
    run(store, 0);
    assertThat(harness.stored(stale)).isPresent();

    harness.healIngests();
    store.remove("A", "n/z/w/f4.txt");
    FileSyncHarness.Run next = run(store, 0);

    assertThat(next.eventsOf(IndexingEventCategory.REMOVED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(stale);
  }

  // regression guard for #2202: downloads finished before the budget's end are taken up
  @Test
  void aFixedBudgetEndingWithDownloadsInFlightStillMovesTheRoundOn() {
    harness.downloadConcurrency(3);
    InMemoryFileStore store = new InMemoryFileStore().withCheckpoints().pageSize(1).container("A");
    for (int i = 1; i <= 9; i++) {
      store.put("A", "f" + i + ".txt", "Text " + i);
    }
    run(store, 0);
    store.put("A", "a1.txt", "Neu eins.").put("A", "a2.txt", "Neu zwei.");
    String first = store.filePathOf("A", "a1.txt");
    String second = store.filePathOf("A", "a2.txt");

    // two downloads stay in flight below the concurrency when the budget ends on the last page
    boolean taken = false;
    for (int runs = 0; runs < 5 && !taken; runs++) {
      run(store, 11);
      taken = harness.stored(first).isPresent() && harness.stored(second).isPresent();
    }

    assertThat(taken).as("the new files are taken up under a fixed budget").isTrue();
  }

  @Test
  void aCheckpointTooLongToKeepIsNamedAndNotKept() {
    InMemoryFileStore store = new InMemoryFileStore().withCheckpoints().pageSize(1).container("A");
    for (int i = 1; i <= 4; i++) {
      store.put("A", "d" + i + ".txt", "Text " + i);
    }

    FileSyncHarness.Run run = harness.fullSync(new LongCheckpoints(store.reset().budget(4)));

    assertThat(run.eventsOf(IndexingEventCategory.REJECTED))
        .extracting(IndexingRunEvent::getMessage)
        .singleElement()
        .satisfies(message -> assertThat(message).contains("zu groß zum Speichern"));
    assertThat(checkpointOf(harness.state(), "A")).isNull();
  }

  /** The path of a stored document whose file no longer exists and that no change reports. */
  private static final String STALE = "mem://A/#alt";

  private static final List<String> NAMES = List.of("a/1.txt", "a/2.txt", "z/3.txt", "z/4.txt");

  /**
   * A store tracked by id whose change log notes every change, over two containers with two folders
   * each; a first full sync has taken everything up.
   */
  private InMemoryFileStore changeLogStore(AbsenceProof proof) {
    InMemoryFileStore store =
        new InMemoryFileStore()
            .withCheckpoints()
            .withFolderMarkers()
            .withStableIds()
            .withChangeFeed()
            .recordingChanges()
            .absenceProof(proof)
            .pageSize(2)
            .container("A")
            .container("B");
    for (String container : List.of("A", "B")) {
      for (String name : NAMES) {
        store.put(container, name, container + " " + name + ".");
      }
    }
    run(store, 0);
    return store;
  }

  /**
   * Leaves a round open after its first run: every file changed, so the budget ends the listing of
   * A behind its first page ({@code a/1.txt}, {@code a/2.txt}); a stale document stands.
   */
  private void openRound(InMemoryFileStore store) {
    harness.store(STALE, "h:0|0");
    for (String container : List.of("A", "B")) {
      for (String name : NAMES) {
        store.put(container, name, container + " " + name + ", zweite, längere Fassung.");
      }
    }
    run(store, 6);
    assertThat(harness.state().isFullSyncInterrupted()).as("the round is open").isTrue();
    assertThat(checkpointOf(harness.state(), "A")).isEqualTo("g0:a/2.txt");
    assertThat(harness.stored(STALE)).isPresent();
  }

  @Test
  void aStaleDocumentStaysAfterARoundOverSeveralRunsWithoutAProof() {
    InMemoryFileStore store = changeLogStore(AbsenceProof.SINGLE_RUN);
    openRound(store);

    FileSyncHarness.Run closing = run(store, 0);

    assertThat(harness.state().isFullSyncInterrupted()).isFalse();
    assertThat(closing.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
    assertThat(harness.stored(STALE)).isPresent();
  }

  @Test
  void aStaleDocumentIsRemovedAtTheEndOfARoundOverSeveralRunsOnceTheChangeLogIsRead() {
    InMemoryFileStore store = changeLogStore(AbsenceProof.CHANGE_FEED);
    openRound(store);

    FileSyncHarness.Run closing = run(store, 0);

    assertThat(store.calls()).as("the round resumes").startsWith("resume A @g0:a/2.txt");
    assertThat(store.calls()).anyMatch(call -> call.startsWith("read stream:A"));
    assertThat(closing.listingComplete()).isTrue();
    assertThat(closing.eventsOf(IndexingEventCategory.REMOVED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(STALE);
    assertThat(harness.state().isFullSyncInterrupted()).isFalse();
    assertThat(harness.storedPaths()).hasSize(8);
  }

  // regression guard for #2268: findings of a log read under the old settings prove nothing
  @Test
  void aRoundsEndWhoseSettingsChangeWhileTheLogIsReadRemovesNothing() {
    InMemoryFileStore store = changeLogStore(AbsenceProof.CHANGE_FEED);
    openRound(store);
    String gone = store.filePathOf("A", "a/1.txt");
    store
        .remove("A", "a/1.txt")
        .beforeNextChangeRead(
            () -> harness.library().updateSourceSettings("{\"auswahl\":\"neu\"}"));

    FileSyncHarness.Run closing = run(store, 0);

    assertThat(store.calls()).anyMatch(call -> call.startsWith("read stream:A"));
    assertThat(closing.eventsOf(IndexingEventCategory.REMOVED))
        .extracting(IndexingRunEvent::getReference)
        .isEmpty();
    assertThat(harness.storedPaths()).contains(gone, STALE);
    assertThat(harness.state().isFullSyncInterrupted()).as("the round stays open").isTrue();
  }

  @Test
  void aFileDeletedInThePartAlreadyListedIsRemovedThroughTheChangeLog() {
    InMemoryFileStore store = changeLogStore(AbsenceProof.CHANGE_FEED);
    openRound(store);
    String deleted = store.filePathOf("A", "a/1.txt");
    store.remove("A", "a/1.txt");

    FileSyncHarness.Run closing = run(store, 0);

    assertThat(closing.eventsOf(IndexingEventCategory.REMOVED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactlyInAnyOrder(deleted, STALE);
    assertThat(harness.storedPaths()).hasSize(7);
  }

  @Test
  void aFileMovedBehindTheCheckpointKeepsItsDocumentAtItsNewPlace() {
    InMemoryFileStore store = changeLogStore(AbsenceProof.CHANGE_FEED);
    openRound(store);
    String moved = store.filePathOf("A", "z/4.txt");
    store.move("A", "z/4.txt", "a/0.txt");

    FileSyncHarness.Run closing = run(store, 0);

    assertThat(closing.eventsOf(IndexingEventCategory.REMOVED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(STALE);
    assertThat(harness.stored(moved)).isPresent();
    assertThat(harness.stored(moved).orElseThrow().getSourceHierarchyPath()).isEqualTo("a");
    assertThat(harness.stored(moved).orElseThrow().getFileName()).isEqualTo("0.txt");
  }

  @Test
  void aNewFileInThePartAlreadyListedIsTakenUpAtTheRoundsEnd() {
    InMemoryFileStore store = changeLogStore(AbsenceProof.CHANGE_FEED);
    openRound(store);
    store.put("A", "a/15.txt", "Neu hinter dem Fortsetzungspunkt.");
    String added = store.filePathOf("A", "a/15.txt");

    FileSyncHarness.Run closing = run(store, 0);

    assertThat(closing.ingested()).contains(added);
    assertThat(harness.stored(added)).isPresent();
    assertThat(harness.state().isFullSyncInterrupted()).isFalse();
  }

  /**
   * A file rejected at a quota at the round's end holds its stream's cursor, not the round: the
   * removals the log reports - one of which may free the room - are applied and the round closes;
   * the next change run reads from the round's start and takes the file once there is room.
   */
  @ParameterizedTest
  @ValueSource(strings = {"library", "owner", "listed size"})
  void aFileHeldAtAQuotaAtTheRoundsEndLetsTheRoundCloseAndKeepsItsStreamsStart(String quota) {
    InMemoryFileStore store = changeLogStore(AbsenceProof.CHANGE_FEED);
    openRound(store);
    Map<String, String> held = harness.state().pendingChangeCursors();
    store.put("A", "a/15.txt", "Neu hinter dem Fortsetzungspunkt, passt nicht mehr.");
    String added = store.filePathOf("A", "a/15.txt");
    switch (quota) {
      case "library" -> harness.rejectAtLibraryQuotaOf(added);
      case "owner" -> harness.rejectAtQuotaOf(added);
      default -> harness.rejectBeforeDownload(added, DocumentIngestResult.QUOTA_EXCEEDED);
    }
    String deleted = store.filePathOf("A", "z/3.txt");
    store.remove("A", "z/3.txt");

    for (int run = 0; run < 3 && harness.state().isFullSyncInterrupted(); run++) {
      run(store, 0);
    }

    assertThat(harness.state().isFullSyncInterrupted()).as("the round closed").isFalse();
    assertThat(harness.stored(deleted)).as("the deletion at the source arrived").isEmpty();
    assertThat(harness.stored(STALE)).isEmpty();
    assertThat(harness.stored(added)).isEmpty();
    assertThat(harness.state().changeCursors().get("stream:A"))
        .as("the stream keeps the cursor held at the round's start")
        .isEqualTo(held.get("stream:A"));

    harness.freeQuota();
    FileSyncHarness.Run next = harness.changeRun(store.reset());

    assertThat(next.ingested()).contains(added);
    assertThat(harness.stored(added)).isPresent();
  }

  /**
   * A file held at a quota in an earlier run of a round lies before the round's start in the change
   * log, so no change run would meet it again: the round still closes, and the next run is a full
   * sync, which takes the file in once there is room.
   */
  @Test
  void aFileHeldAtAQuotaInAnEarlierRunOfTheRoundMakesTheNextRunAFullSync() {
    InMemoryFileStore store = changeLogStore(AbsenceProof.CHANGE_FEED);
    String held = store.filePathOf("A", "a/1.txt");
    harness.rejectAtLibraryQuotaOf(held);
    openRound(store);

    for (int run = 0; run < 3 && harness.state().isFullSyncInterrupted(); run++) {
      run(store, 0);
    }

    assertThat(harness.state().isFullSyncInterrupted()).as("the round closed").isFalse();
    assertThat(harness.state().canReadChanges(STREAMS, WEEK, FileSyncHarness.NOW))
        .as("the next run is a full sync")
        .isFalse();

    harness.freeQuota();
    FileSyncHarness.Run next = run(store, 0);

    assertThat(next.ingested()).contains(held);
    assertThat(harness.state().canReadChanges(STREAMS, WEEK, FileSyncHarness.NOW)).isTrue();
  }

  /** A file held only at the round's end keeps its stream's start; change runs go on. */
  @Test
  void aFileHeldAtAQuotaOnlyAtTheRoundsEndLeavesTheChangeRunsInPlace() {
    InMemoryFileStore store = changeLogStore(AbsenceProof.CHANGE_FEED);
    openRound(store);
    store.put("A", "a/15.txt", "Neu hinter dem Fortsetzungspunkt, passt nicht mehr.");
    harness.rejectAtLibraryQuotaOf(store.filePathOf("A", "a/15.txt"));

    for (int run = 0; run < 3 && harness.state().isFullSyncInterrupted(); run++) {
      run(store, 0);
    }

    assertThat(harness.state().isFullSyncInterrupted()).as("the round closed").isFalse();
    assertThat(harness.state().canReadChanges(STREAMS, WEEK, FileSyncHarness.NOW)).isTrue();
  }

  private static final Set<String> STREAMS = Set.of("stream:A", "stream:B");
  private static final Duration WEEK = Duration.ofDays(7);

  @Test
  void aChangeOfStructureEndsTheRoundWithoutRemovingAnything() {
    InMemoryFileStore store = changeLogStore(AbsenceProof.CHANGE_FEED);
    openRound(store);
    Map<String, String> held = harness.state().pendingChangeCursors();
    store.remove("A", "a/1.txt");
    store.structureChanged();

    FileSyncHarness.Run closing = run(store, 0);

    assertThat(closing.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
    assertThat(harness.storedPaths()).hasSize(9);
    assertThat(harness.state().isFullSyncInterrupted()).isFalse();
    assertThat(harness.state().changeCursors())
        .as("the next change run reads from where the round began")
        .isEqualTo(held);
    assertThat(closing.eventsOf(IndexingEventCategory.SUMMARY))
        .extracting(IndexingRunEvent::getMessage)
        .contains(FileSync.UNPROVEN_CHANGE_LOG_MESSAGE);
  }

  @Test
  void anExpiredCursorEndsTheRoundWithoutRemovingAnything() {
    InMemoryFileStore store = changeLogStore(AbsenceProof.CHANGE_FEED);
    openRound(store);
    store.remove("A", "a/1.txt");
    store.expireCursors();

    FileSyncHarness.Run closing = run(store, 0);

    assertThat(closing.failure()).isNull();
    assertThat(closing.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
    assertThat(harness.storedPaths()).hasSize(9);
    assertThat(harness.state().isFullSyncInterrupted()).isFalse();
  }

  @Test
  void aTransientFailureOfTheChangeLogKeepsTheRoundOpenAndTheNextRunListsNoContainer() {
    InMemoryFileStore store = changeLogStore(AbsenceProof.CHANGE_FEED);
    openRound(store);
    String deleted = store.filePathOf("A", "a/1.txt");
    store.remove("A", "a/1.txt");

    FileSyncHarness.Run failed =
        harness.fullSync(
            new FailingChangeLog(
                store.reset().budget(0),
                "stream:B",
                () -> new FileAccessException.Transient("Der Dienst antwortet nicht.")));

    assertThat(failed.failure()).isNull();
    assertThat(failed.listingComplete()).as("nothing reconciled").isNull();
    assertThat(failed.eventsOf(IndexingEventCategory.REMOVED))
        .as("a removal read before the failure is not applied")
        .isEmpty();
    assertThat(harness.state().isFullSyncInterrupted()).isTrue();

    FileSyncHarness.Run next = run(store, 0);

    assertThat(store.calls())
        .as("no container is listed again")
        .allMatch(call -> call.startsWith("read ") || call.startsWith("reachable "));
    assertThat(store.meter().requests()).isEqualTo(store.calls().size());
    assertThat(next.eventsOf(IndexingEventCategory.REMOVED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactlyInAnyOrder(deleted, STALE);
    assertThat(harness.state().isFullSyncInterrupted()).isFalse();
  }

  @Test
  void aFileFailingTransientlyInTheChangeLogKeepsTheRoundOpen() {
    InMemoryFileStore store = changeLogStore(AbsenceProof.CHANGE_FEED);
    openRound(store);
    store.put("A", "a/15.txt", "Neu hinter dem Fortsetzungspunkt.");
    String added = store.filePathOf("A", "a/15.txt");
    harness.failIngestOf(added);

    FileSyncHarness.Run failed = run(store, 0);

    assertThat(failed.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
    assertThat(harness.state().isFullSyncInterrupted()).isTrue();

    harness.healIngests();
    FileSyncHarness.Run next = run(store, 0);

    assertThat(next.ingested()).containsExactly(added);
    assertThat(next.eventsOf(IndexingEventCategory.REMOVED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(STALE);
    assertThat(harness.state().isFullSyncInterrupted()).isFalse();
  }

  @Test
  void aBudgetEndingWhileTheChangeLogIsReadKeepsTheRoundOpen() {
    InMemoryFileStore store = changeLogStore(AbsenceProof.CHANGE_FEED);
    openRound(store);
    String deleted = store.filePathOf("A", "a/1.txt");
    store.remove("A", "a/1.txt");

    FileSyncHarness.Run spent =
        harness.fullSync(
            new FailingChangeLog(
                store.reset().budget(0),
                "stream:B",
                () -> RequestBudgetExhaustedException.requests(20)));

    assertThat(spent.failure()).isNull();
    assertThat(spent.eventsOf(IndexingEventCategory.BUDGET_EXHAUSTED)).hasSize(1);
    assertThat(spent.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
    assertThat(harness.storedPaths()).hasSize(9);
    assertThat(harness.state().isFullSyncInterrupted()).isTrue();

    FileSyncHarness.Run next = run(store, 0);

    assertThat(store.calls())
        .noneMatch(call -> call.startsWith("list") || call.startsWith("resume"));
    assertThat(next.eventsOf(IndexingEventCategory.REMOVED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactlyInAnyOrder(deleted, STALE);
  }

  // regression guard: a container completed by a run that then failed keeps its presence, so the
  // round's end removes none of its documents
  @Test
  void aContainerCompletedByARunThatThenFailedKeepsItsDocumentsAtTheRoundsEnd() {
    InMemoryFileStore store = changeLogStore(AbsenceProof.CHANGE_FEED);
    for (String container : List.of("A", "B")) {
      for (String name : NAMES) {
        store.put(container, name, container + " " + name + ", zweite, längere Fassung.");
      }
    }
    // two start cursors, two pages and four downloads of A pass, the first listing of B is refused
    FileSyncHarness.Run failed = run(store.rejectCredentialsAfter(8), 0);
    assertThat(failed.failure()).isNotNull();
    assertThat(harness.state().completedScopeKeys()).containsExactly("A");
    store.acceptCredentials();

    FileSyncHarness.Run closing = run(store, 0);

    assertThat(store.calls())
        .as("A is not listed again")
        .noneMatch(call -> call.startsWith("list A") || call.startsWith("resume A"));
    assertThat(closing.eventsOf(IndexingEventCategory.REMOVED))
        .extracting(IndexingRunEvent::getReference)
        .isEmpty();
    assertThat(harness.storedPaths()).hasSize(8);
    assertThat(harness.state().isFullSyncInterrupted()).isFalse();
  }

  // regression guard: a container out of reach at the round's end must not hold the round forever
  @Test
  void aContainerOutOfReachAtTheRoundsEndCountsAsNotListedAndTheNextRoundListsTheOthers() {
    InMemoryFileStore store = changeLogStore(AbsenceProof.CHANGE_FEED);
    openRound(store);
    harness.fullSync(
        new FailingChangeLog(
            store.reset().budget(0),
            "stream:A",
            () -> new FileAccessException.Transient("Der Dienst antwortet nicht.")));
    assertThat(harness.state().completedScopeKeys()).containsExactlyInAnyOrder("A", "B");
    store.denyListing("B");
    store.put("A", "a/15.txt", "Neu hinter dem Fortsetzungspunkt.");

    FileSyncHarness.Run unreachable = run(store, 0);

    assertThat(store.calls())
        .as("no stream is read while a container is out of reach")
        .noneMatch(call -> call.startsWith("read"));
    assertThat(unreachable.listingComplete()).isFalse();
    assertThat(unreachable.unlistedContainerKeys()).containsExactly("B");
    assertThat(unreachable.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();

    FileSyncHarness.Run next = run(store, 0);

    assertThat(store.calls()).as("a new round lists A again").contains("list A");
    assertThat(next.ingested()).contains(store.filePathOf("A", "a/15.txt"));
    assertThat(next.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
    assertThat(harness.stored(STALE)).isPresent();
  }

  @Test
  void aRoundWithoutAHeldCursorForEveryStreamStartsOver() {
    InMemoryFileStore store = changeLogStore(AbsenceProof.CHANGE_FEED);
    openRound(store);
    String streamA = harness.state().pendingChangeCursors().get("stream:A");
    harness.state().holdPendingChangeCursors(Map.of("stream:A", streamA));

    FileSyncHarness.Run next = run(store, 0);

    assertThat(store.calls())
        .as("new start cursors, then every container from its first page")
        .startsWith("startCursor stream:A", "startCursor stream:B", "list A")
        .noneMatch(call -> call.startsWith("resume") || call.startsWith("read"));
    assertThat(next.eventsOf(IndexingEventCategory.REMOVED))
        .as("this run listed everything alone")
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(STALE);
    assertThat(harness.state().isFullSyncInterrupted()).isFalse();
  }

  @Test
  void aRunThatListsEveryContainerAloneReadsNoChangeLog() {
    InMemoryFileStore store = changeLogStore(AbsenceProof.CHANGE_FEED);
    harness.store(STALE, "h:0|0");
    String deleted = store.filePathOf("A", "a/1.txt");
    store.remove("A", "a/1.txt");

    FileSyncHarness.Run run = run(store, 0);

    assertThat(store.calls()).noneMatch(call -> call.startsWith("read"));
    assertThat(run.eventsOf(IndexingEventCategory.REMOVED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactlyInAnyOrder(STALE, deleted);
  }

  /**
   * Delegates to {@code store}; the first read of {@code feedKey} throws what {@code failure}
   * gives.
   */
  private static final class FailingChangeLog implements FileStore {

    private final FileStore store;
    private final String feedKey;
    private Supplier<Exception> failure;

    private FailingChangeLog(FileStore store, String feedKey, Supplier<Exception> failure) {
      this.store = store;
      this.feedKey = feedKey;
      this.failure = failure;
    }

    @Override
    public List<FileContainer> containers() {
      return store.containers();
    }

    @Override
    public void recall(FileContainer container, Map<String, String> subtreeMarkers) {
      store.recall(container, subtreeMarkers);
    }

    @Override
    public FilePage list(FileContainer container, String continuation)
        throws FileAccessException, InterruptedException {
      return store.list(container, continuation);
    }

    @Override
    public FilePage resume(FileContainer container, String checkpoint)
        throws FileAccessException, InterruptedException {
      return store.resume(container, checkpoint);
    }

    @Override
    public AbsenceProof absenceProof() {
      return store.absenceProof();
    }

    @Override
    public FileEntry head(FileContainer container, String id)
        throws FileAccessException, InterruptedException {
      return store.head(container, id);
    }

    @Override
    public FetchedFile fetch(FileEntry entry, long maxBytes)
        throws FileAccessException, InterruptedException {
      return store.fetch(entry, maxBytes);
    }

    @Override
    public Optional<ChangeFeed> changes() {
      ChangeFeed feed = store.changes().orElseThrow();
      return Optional.of(
          new ChangeFeed() {
            @Override
            public String feedKey(FileContainer container) {
              return feed.feedKey(container);
            }

            @Override
            public String startCursor(String key) throws FileAccessException, InterruptedException {
              return feed.startCursor(key);
            }

            @Override
            public ChangePage read(String key, String cursor)
                throws FileAccessException, InterruptedException {
              if (failure != null && key.equals(feedKey)) {
                Exception thrown = failure.get();
                failure = null;
                if (thrown instanceof FileAccessException access) {
                  throw access;
                }
                throw (RuntimeException) thrown;
              }
              return feed.read(key, cursor);
            }

            @Override
            public void requireReachable(FileContainer container)
                throws FileAccessException, InterruptedException {
              feed.requireReachable(container);
            }
          });
    }

    @Override
    public io.opaa.sourceaccess.SourceRequestMeter meter() {
      return store.meter();
    }

    @Override
    public void close() {}
  }

  /** Pads every checkpoint beyond what the core keeps. */
  private record LongCheckpoints(FileStore store) implements FileStore {

    @Override
    public java.util.List<FileContainer> containers() {
      return store.containers();
    }

    @Override
    public FilePage list(FileContainer container, String continuation)
        throws FileAccessException, InterruptedException {
      FilePage page = store.list(container, continuation);
      return new FilePage(
          page.entries(),
          page.next(),
          page.checkpoint() + "x".repeat(FilePage.MAX_CHECKPOINT_LENGTH),
          page.unchangedSubtrees(),
          page.listedSubtrees());
    }

    @Override
    public FileEntry head(FileContainer container, String id)
        throws FileAccessException, InterruptedException {
      return store.head(container, id);
    }

    @Override
    public FetchedFile fetch(FileEntry entry, long maxBytes)
        throws FileAccessException, InterruptedException {
      return store.fetch(entry, maxBytes);
    }

    @Override
    public io.opaa.sourceaccess.SourceRequestMeter meter() {
      return store.meter();
    }

    @Override
    public void close() {}
  }
}
