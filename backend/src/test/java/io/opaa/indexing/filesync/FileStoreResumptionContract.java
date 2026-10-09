package io.opaa.indexing.filesync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingRunEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The contract of a store whose full sync spans several runs ({@link FileStore#resume}): with a
 * request budget below what one listing of every folder costs, a round still completes after
 * finitely many runs without listing finished folders again, and no document is removed whose file
 * still exists under its identity - whatever is deleted, moved behind the checkpoint or expired
 * between two runs. Every file connector whose store gives checkpoints extends it once per test
 * level.
 */
public abstract class FileStoreResumptionContract extends FileStoreContract {

  /** Folders per container, each with one file. */
  private static final int FOLDERS = 8;

  /** Runs a round may take before the contract gives up. */
  private static final int MAX_RUNS = 30;

  /** Per container, the names of the files that exist at the source right now. */
  private final List<Set<String>> live = List.of(new LinkedHashSet<>(), new LinkedHashSet<>());

  /** The documents removed so far that the contract checks against the source. */
  private final List<String> removed = new ArrayList<>();

  /** What a series of budgeted runs did. */
  private record Rounds(int runs, long requests, boolean complete) {}

  // regression guard for #2202: a run that ends at its budget lists the same first folders again
  @Test
  void aLibraryWithMoreFoldersThanTheBudgetCompletesOverSeveralRunsWithoutRelistingFinishedOnes()
      throws Exception {
    fillFolders("Inhalt");
    int cost = unboundedCost();

    Rounds rounds = untilComplete(budget(cost));

    assertThat(rounds.complete())
        .as("the round completes within %s runs of %s requests each", MAX_RUNS, budget(cost))
        .isTrue();
    assertThat(rounds.runs()).as("the budget splits the round").isGreaterThan(1);
    assertThat(harness.storedPaths()).containsExactlyInAnyOrderElementsOf(identities());
    assertThat(rounds.requests())
        .as("finished folders are not listed again; one listing of everything costs %s", cost)
        .isLessThanOrEqualTo(2L * cost);
    assertThat(removed).isEmpty();
  }

  // regression guard for #2202: a folder that stays unreadable keeps the other containers listed
  @Test
  void aFolderThatStaysUnreadableKeepsNoOtherContainerFromBeingListed() throws Exception {
    fillFolders("Inhalt");
    unbounded();
    fixture.denyListingOf(1, "ordner-3");
    // a change below makes a store with folder markers list the folder again
    put(1, "ordner-3/geaendert.txt", "Geändert.");
    unbounded();
    String added = "ordner-0/neu.txt";
    put(0, added, "Neu in einem lesbaren Bereich.");

    boolean taken = false;
    for (int runs = 0; runs < 3 && !taken; runs++) {
      unbounded();
      taken = harness.stored(fixture.filePath(0, added)).isPresent();
    }

    assertThat(taken).as("a new file in a listable container is taken up").isTrue();
    assertThat(removed).isEmpty();
  }

  // regression guard for #2202: a small budget and an unreadable folder still take up new files
  @Test
  void underASmallBudgetAFolderThatStaysUnreadableKeepsNoNewFileOut() throws Exception {
    fillFolders("Inhalt");
    int budget = budget(unboundedCost());
    unbounded();
    fixture.denyListingOf(1, "ordner-3");
    put(1, "ordner-3/geaendert.txt", "Geändert.");
    // the round has met the unreadable folder before the new files appear
    for (int runs = 0; runs < 6; runs++) {
      budgeted(budget);
    }
    List<String> added = List.of("ordner-0/neu.txt", "ordner-6/neu.txt");
    for (String name : added) {
      put(0, name, "Neu in einem lesbaren Bereich: " + name);
    }

    boolean taken = false;
    for (int runs = 0; runs < MAX_RUNS && !taken; runs++) {
      budgeted(budget);
      taken =
          added.stream().allMatch(name -> harness.stored(fixture.filePath(0, name)).isPresent());
    }

    assertThat(taken).as("new files in a listable container are taken up").isTrue();
    assertThat(removed).isEmpty();
  }

  @Test
  void anUnchangedRunAfterTheRoundCostsOneRequestPerContainer() throws Exception {
    assumeTrue(fixture.reportsFolders(), "only a store that reports folders skips unchanged ones");
    fillFolders("Inhalt");
    int budget = budget(unboundedCost());
    assertThat(untilComplete(budget).complete()).isTrue();

    FileStore store = fixture.open(PAGE_SIZE, budget);
    FileSyncHarness.Run unchanged = harness.fullSync(store);

    assertThat(unchanged.failure()).isNull();
    assertThat(unchanged.listingComplete()).isTrue();
    assertThat(unchanged.ingested()).isEmpty();
    assertThat(store.meter().requests()).isLessThanOrEqualTo(3);
    assertThat(unchanged.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
  }

  @Test
  void aFileDeletedAtTheSourceIsRemovedOnlyOnceItsAbsenceIsProven() throws Exception {
    fillFolders("Inhalt");
    unbounded();
    fillFolders("Zweite, längere Fassung");
    int budget = budget(unboundedCost());
    String early = name(0);
    String late = name(FOLDERS - 1);

    FileSyncHarness.Run first = budgeted(budget);
    assertThat(harness.state().isFullSyncInterrupted()).as("the round is still open").isTrue();
    assertThat(first.ingested()).contains(fixture.filePath(0, early));
    remove(0, early);
    remove(1, late);
    Rounds rounds = untilComplete(budget);

    assertThat(rounds.complete()).isTrue();
    if (absenceProof() == AbsenceProof.SINGLE_RUN) {
      assertThat(removed).as("a round over several runs proves no absence").isEmpty();
    }
    if (absenceProof() == AbsenceProof.CHANGE_FEED) {
      assertThat(removed)
          .as("the change log since the round began proves both absences at its end")
          .containsExactlyInAnyOrder(fixture.filePath(0, early), fixture.filePath(1, late));
    }
    unbounded();
    unbounded();
    assertThat(removed)
        .containsExactlyInAnyOrder(fixture.filePath(0, early), fixture.filePath(1, late));
    assertThat(harness.storedPaths()).containsExactlyInAnyOrderElementsOf(identities());
  }

  @Test
  void aFileMovedBehindTheCheckpointKeepsItsDocument() throws Exception {
    fillFolders("Inhalt");
    unbounded();
    fillFolders("Zweite, längere Fassung");
    int budget = budget(unboundedCost());

    FileSyncHarness.Run first = budgeted(budget);
    assertThat(first.ingested())
        .as("the first folder is behind the checkpoint, the last one not yet")
        .contains(fixture.filePath(0, name(0)))
        .doesNotContain(fixture.filePath(0, name(FOLDERS - 1)));
    String moved = "ordner-0/verschoben.txt";
    move(0, name(FOLDERS - 1), moved);
    assertThat(untilComplete(budget).complete()).isTrue();
    unbounded();
    unbounded();

    assertThat(harness.storedPaths()).containsExactlyInAnyOrderElementsOf(identities());
    assertThat(harness.stored(fixture.filePath(0, moved))).isPresent();
  }

  @Test
  void anExpiredCheckpointStartsTheContainerOverAndRemovesNothing() throws Exception {
    fillFolders("Inhalt");
    unbounded();
    fillFolders("Zweite, längere Fassung");
    int budget = budget(unboundedCost());

    budgeted(budget);
    assertThat(harness.state().isFullSyncInterrupted()).isTrue();
    fixture.expireCheckpoints();
    FileSyncHarness.Run restarted = budgeted(budget);
    Rounds rounds = untilComplete(budget);

    assertThat(restarted.eventsOf(IndexingEventCategory.REJECTED))
        .extracting(IndexingRunEvent::getMessage)
        .anySatisfy(message -> assertThat(message).contains("beginnt neu"));
    assertThat(rounds.complete()).isTrue();
    assertThat(removed).isEmpty();
    assertThat(harness.storedPaths()).containsExactlyInAnyOrderElementsOf(identities());
  }

  /** {@code FOLDERS} folders per container, one file of {@code text} in each. */
  private void fillFolders(String text) throws Exception {
    for (int container = 0; container < 2; container++) {
      for (int i = 0; i < FOLDERS; i++) {
        put(container, name(i), text + " " + container + "/" + i);
      }
    }
  }

  private static String name(int folder) {
    return "ordner-" + folder + "/datei-" + folder + ".txt";
  }

  private void put(int container, String name, String text) throws Exception {
    fixture.put(container, name, text);
    live.get(container).add(name);
  }

  private void remove(int container, String name) throws Exception {
    fixture.remove(container, name);
    live.get(container).remove(name);
  }

  private void move(int container, String from, String to) throws Exception {
    fixture.move(container, from, to);
    live.get(container).remove(from);
    live.get(container).add(to);
  }

  /** The identity of every file at the source right now. */
  private List<String> identities() {
    List<String> paths = new ArrayList<>();
    for (int container = 0; container < 2; container++) {
      for (String name : live.get(container)) {
        paths.add(fixture.filePath(container, name));
      }
    }
    return paths;
  }

  /** What one listing of everything costs, measured on a library of its own. */
  private int unboundedCost() throws Exception {
    FileStore store = fixture.open(PAGE_SIZE);
    new FileSyncHarness().fullSync(store);
    return store.meter().requests();
  }

  private AbsenceProof absenceProof() throws Exception {
    try (FileStore probe = fixture.open(PAGE_SIZE)) {
      return probe.absenceProof();
    }
  }

  private int budget(int cost) {
    return Math.max(cost / 3, fixture.minimumBudget());
  }

  private FileSyncHarness.Run unbounded() throws Exception {
    return checked(harness.fullSync(fixture.open(PAGE_SIZE)));
  }

  private FileSyncHarness.Run budgeted(int budget) throws Exception {
    return checked(harness.fullSync(fixture.open(PAGE_SIZE, budget)));
  }

  /** The run did not fail, and nothing it removed still exists under its identity. */
  private FileSyncHarness.Run checked(FileSyncHarness.Run run) {
    assertThat(run.failure()).isNull();
    List<String> gone =
        run.eventsOf(IndexingEventCategory.REMOVED).stream()
            .map(IndexingRunEvent::getReference)
            .toList();
    assertThat(gone)
        .as("removed although the file exists")
        .doesNotContainAnyElementsOf(identities());
    removed.addAll(gone);
    return run;
  }

  /** Budgeted runs until the round completes, at most {@link #MAX_RUNS}. */
  private Rounds untilComplete(int budget) throws Exception {
    long requests = 0;
    for (int runs = 1; runs <= MAX_RUNS; runs++) {
      FileStore store = fixture.open(PAGE_SIZE, budget);
      checked(harness.fullSync(store));
      requests += store.meter().requests();
      if (!harness.state().isFullSyncInterrupted()) {
        return new Rounds(runs, requests, true);
      }
    }
    return new Rounds(MAX_RUNS, requests, false);
  }
}
