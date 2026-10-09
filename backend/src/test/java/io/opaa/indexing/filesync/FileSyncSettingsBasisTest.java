package io.opaa.indexing.filesync;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingRunEvent;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A sync state only means something under the settings it was written with (#2268): a run under
 * other settings neither continues the round, nor reads the change cursors, nor reconciles against
 * the presence of that state, and a run whose settings change while it goes removes nothing.
 */
class FileSyncSettingsBasisTest {

  private static final String FIRST = "{\"folders\":[\"/erste\"]}";
  private static final String SECOND = "{\"folders\":[\"/zweite\"]}";

  private final FileSyncHarness harness;

  FileSyncSettingsBasisTest() throws Exception {
    harness = new FileSyncHarness();
    harness.library().updateSourceSettings(FIRST);
  }

  // regression guard for #2268: the presence of a round begun under other settings proves nothing
  @Test
  void aRoundBegunUnderOtherSettingsStartsOverAndRemovesNoDocumentWhoseFileExists() {
    String hidden = InMemoryFileStore.filePath("A", "a2.txt");
    harness.store(hidden, "h:0|0");
    InMemoryFileStore narrow = round().put("A", "a1.txt", "A1.");
    harness.fullSync(narrow.reset().budget(4));
    assertThat(harness.state().completedScopeKeys()).containsExactly("A");
    assertThat(harness.state().isFullSyncInterrupted()).isTrue();

    harness.library().updateSourceSettings(SECOND);
    InMemoryFileStore wide = round().put("A", "a1.txt", "A1.").put("A", "a2.txt", "A2.");
    FileSyncHarness.Run next = harness.fullSync(wide.reset());

    assertThat(next.failure()).isNull();
    assertThat(next.eventsOf(IndexingEventCategory.REMOVED))
        .extracting(IndexingRunEvent::getReference)
        .isEmpty();
    assertThat(harness.storedPaths()).contains(hidden);
    assertThat(wide.calls()).as("the round starts over").contains("list A");
  }

  // regression guard for #2268: a round over several runs rests on presence the store's proof wrote
  @Test
  void aRoundBegunUnderAnotherAbsenceProofStartsOver() {
    InMemoryFileStore store =
        round().put("A", "a1.txt", "A1.").put("A", "a2.txt", "A2.").put("B", "b4.txt", "B4.");
    harness.fullSync(store.reset());
    assertThat(harness.storedPaths()).hasSize(6);
    store.absenceProof(AbsenceProof.SINGLE_RUN).rejectCredentialsAfter(2);
    FileSyncHarness.Run failed = harness.fullSync(store.reset());
    assertThat(failed.failure()).isNotBlank();
    assertThat(harness.state().completedScopeKeys()).containsExactly("A");

    store.acceptCredentials().absenceProof(AbsenceProof.LOCATION_IDENTITY);
    FileSyncHarness.Run next = harness.fullSync(store.reset());

    assertThat(next.failure()).isNull();
    assertThat(next.eventsOf(IndexingEventCategory.REMOVED))
        .extracting(IndexingRunEvent::getReference)
        .isEmpty();
    assertThat(harness.storedPaths()).hasSize(6);
    assertThat(store.calls()).as("the round starts over").contains("list A");
  }

  // regression guard for #2268: change cursors read under other settings miss what they now cover
  @Test
  void aChangeRunUnderOtherSettingsReadsNoCursorAndAFullSyncFollows() {
    InMemoryFileStore store =
        new InMemoryFileStore()
            .container("A")
            .withChangeFeed()
            .put("A", "a.txt", "Erste Fassung.")
            .put("A", "b.txt", "Zweiter Text.");
    harness.fullSync(store);
    assertThat(harness.state().canReadChanges(Set.of("stream:A"), Duration.ofDays(7), now()))
        .isTrue();

    harness.library().updateSourceSettings(SECOND);
    store.remove("A", "b.txt").changed("A", "b.txt");
    FileSyncHarness.Run run = harness.changeRun(store.reset());

    assertThat(store.calls()).noneMatch(call -> call.startsWith("read "));
    assertThat(run.eventsOf(IndexingEventCategory.REMOVED))
        .extracting(IndexingRunEvent::getReference)
        .isEmpty();
    assertThat(harness.state().canReadChanges(Set.of("stream:A"), Duration.ofDays(7), now()))
        .isFalse();
  }

  // regression guard for #2268: a listing that began under the old settings proves no absence
  @Test
  void aFullSyncWhoseSettingsChangeWhileItGoesRemovesNothing() {
    String stale = InMemoryFileStore.filePath("A", "alt.txt");
    harness.store(stale, "h:0|0");
    InMemoryFileStore store = new InMemoryFileStore().container("A").put("A", "a1.txt", "A1.");

    FileSyncHarness.Run changed =
        harness.fullSync(
            new BeforeListingStore(
                store, "A", () -> harness.library().updateSourceSettings(SECOND)));

    assertThat(changed.failure()).isNull();
    assertThat(changed.eventsOf(IndexingEventCategory.REMOVED))
        .extracting(IndexingRunEvent::getReference)
        .isEmpty();
    assertThat(changed.listingComplete()).isFalse();
    assertThat(harness.storedPaths()).contains(stale);

    FileSyncHarness.Run next = harness.fullSync(store.reset());

    assertThat(next.listingComplete()).isTrue();
    assertThat(harness.storedPaths()).doesNotContain(stale);
  }

  // regression guard for #2268: removals a change log reported under the old settings wait
  @Test
  void aChangeRunWhoseSettingsChangeWhileItGoesRemovesNothingAndKeepsItsCursors() {
    InMemoryFileStore store =
        new InMemoryFileStore()
            .container("A")
            .withChangeFeed()
            .put("A", "a.txt", "Erste Fassung.")
            .put("A", "b.txt", "Zweiter Text.");
    harness.fullSync(store);
    var before = harness.state().changeCursors();

    store
        .remove("A", "b.txt")
        .changed("A", "b.txt")
        .beforeNextChangeRead(() -> harness.library().updateSourceSettings(SECOND));
    FileSyncHarness.Run run = harness.changeRun(store.reset());

    assertThat(run.eventsOf(IndexingEventCategory.REMOVED))
        .extracting(IndexingRunEvent::getReference)
        .isEmpty();
    assertThat(harness.storedPaths()).contains(InMemoryFileStore.filePath("A", "b.txt"));
    assertThat(harness.state().changeCursors()).isEqualTo(before);
  }

  private static InMemoryFileStore round() {
    return new InMemoryFileStore()
        .withCheckpoints()
        .absenceProof(AbsenceProof.LOCATION_IDENTITY)
        .pageSize(2)
        .container("A")
        .container("B")
        .put("B", "b1.txt", "B1.")
        .put("B", "b2.txt", "B2.")
        .put("B", "b3.txt", "B3.");
  }

  private static java.time.Instant now() {
    return FileSyncHarness.NOW;
  }
}
