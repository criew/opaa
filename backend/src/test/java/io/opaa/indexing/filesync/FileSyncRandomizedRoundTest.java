package io.opaa.indexing.filesync;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingRunEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Full syncs over several runs against the reference store under a budget that changes from run to
 * run and with one to three downloads in flight, while between and during the runs files are added,
 * changed, moved within and across containers, deleted, folders renamed, documents deleted by hand,
 * containers made unlistable for a while or for good, downloads and ingests failing, runs failing
 * and event runs coming in - with a fixed start value per scenario, named in every failure. No
 * document is removed whose file exists under the same identity; for a store whose identity is the
 * location, under the same identity since the round began. A file that rests in a listable
 * container is taken up; once the source stays still and every container is listable, the bestand
 * matches it. {@code -Dopaa.filesync.randomSeeds}, {@code .randomRuns} and {@code .firstSeed} (or
 * the environment variables {@code OPAA_FILESYNC_RANDOM_*}) widen the search.
 */
class FileSyncRandomizedRoundTest {

  private static final int SEEDS = setting("opaa.filesync.randomSeeds", 8);
  private static final int RUNS = setting("opaa.filesync.randomRuns", 40);
  private static final long FIRST_SEED = setting("opaa.filesync.firstSeed", 20261004);

  private static final List<String> FOLDERS =
      List.of("", "a", "b", "c", "a/x", "b/y", "c/z/w", "r1", "r2/s");
  private static final List<String> CONTAINERS = List.of("A", "B");

  private int multiRunRounds;
  private int removals;
  private int lockedOut;

  @Test
  void aStoreTrackedByIdentityRemovesNothingThatStillExists() {
    for (long seed = FIRST_SEED; seed < FIRST_SEED + SEEDS; seed++) {
      new Scenario(seed, false).play();
    }
    assertThat(multiRunRounds).as("rounds over several runs were exercised").isPositive();
    assertThat(removals).as("removals were exercised").isPositive();
    assertThat(lockedOut).as("containers locked out for good were exercised").isPositive();
  }

  @Test
  void aStoreIdentifiedByLocationRemovesNothingThatExistedSinceTheRoundBegan() {
    for (long seed = FIRST_SEED; seed < FIRST_SEED + SEEDS; seed++) {
      new Scenario(seed, true).play();
    }
    assertThat(multiRunRounds).as("rounds over several runs were exercised").isPositive();
    assertThat(removals).as("removals were exercised").isPositive();
    assertThat(lockedOut).as("containers locked out for good were exercised").isPositive();
  }

  /** One seeded sequence of runs and changes over a fresh library. */
  private final class Scenario {

    private final long seed;
    private final boolean byLocation;
    private final Random random;
    private final InMemoryFileStore store;
    private final FileSyncHarness harness;

    /** Per container, the names that exist at the source. */
    private final Map<String, Set<String>> live = new HashMap<>();

    /**
     * Per container, every name ever used; a store with stable ids keeps an id at a freed name, so
     * a new file there would share an identity with the one that left.
     */
    private final Map<String, Set<String>> used = new HashMap<>();

    /** Per identity, the run before which it was last created. */
    private final Map<String, Integer> createdBefore = new HashMap<>();

    /** The container that becomes unlistable for good, if any, and from which run on. */
    private final String lockedOutContainer;

    private final int lockedOutFrom;
    private final Set<String> deniedForAWhile = new HashSet<>();
    private final Set<String> unreadable = new HashSet<>();

    private int run;
    private int roundStart;
    private int version;
    private int folderNames;

    private Scenario(long seed, boolean byLocation) {
      this.seed = seed;
      this.byLocation = byLocation;
      this.random = new Random(seed);
      this.store = new InMemoryFileStore().withCheckpoints().pageSize(1 + random.nextInt(3));
      if (byLocation) {
        store.absenceProof(AbsenceProof.LOCATION_IDENTITY);
      } else {
        store.withFolderMarkers().withStableIds();
      }
      try {
        this.harness = new FileSyncHarness().downloadConcurrency(1 + random.nextInt(3));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
      boolean lockOut = random.nextInt(3) == 0;
      this.lockedOutContainer = lockOut ? CONTAINERS.get(random.nextInt(CONTAINERS.size())) : null;
      this.lockedOutFrom = 1 + random.nextInt(Math.max(1, RUNS / 2));
      CONTAINERS.forEach(
          container -> {
            store.container(container);
            live.put(container, new LinkedHashSet<>());
            used.put(container, new HashSet<>());
          });
    }

    void play() {
      for (int i = 0; i < 8; i++) {
        add();
      }
      for (run = 1; run <= RUNS; run++) {
        if (run > 1) {
          change();
        }
        if (lockedOutContainer != null && run == lockedOutFrom) {
          store.denyListing(lockedOutContainer);
          lockedOut++;
        }
        int kind = random.nextInt(10);
        if (kind == 0) {
          failingRun();
        } else {
          if (kind == 1) {
            eventRun();
          }
          runOnce(random.nextInt(5) == 0 ? 0 : 3 + random.nextInt(25), kind == 2);
        }
      }
      quiet();
    }

    /** The source stays still, every passing failure ends; only a locked-out container stays. */
    private void quiet() {
      store.acceptCredentials();
      harness.healIngests();
      unreadable.forEach(store::allowReading);
      deniedForAWhile.forEach(store::allowListing);
      for (int i = 0; i < 4; i++, run++) {
        runOnce(0, false);
      }
      Set<String> stored = new HashSet<>(harness.storedPaths());
      for (String container : CONTAINERS) {
        if (container.equals(lockedOutContainer) && run > lockedOutFrom) {
          continue;
        }
        assertThat(stored)
            .as("seed %s: every file of the listable container %s is taken up", seed, container)
            .containsAll(identitiesOf(container));
      }
      if (lockedOutContainer == null) {
        assertThat(harness.storedPaths())
            .as("seed %s: once the source stays still, the bestand matches it", seed)
            .containsExactlyInAnyOrderElementsOf(identities());
      }
    }

    private void runOnce(int budget, boolean deletionDuringTheRun) {
      if (harness.state().scanProgress() == null) {
        roundStart = run;
      }
      boolean wasOpen = harness.state().isFullSyncInterrupted();
      FileStore opened = store.reset().budget(budget);
      if (deletionDuringTheRun) {
        String container = CONTAINERS.get(random.nextInt(CONTAINERS.size()));
        opened = new BeforeListingStore(opened, container, this::forgetOne);
      }
      FileSyncHarness.Run result = harness.fullSync(opened);
      assertThat(result.failure()).as("seed %s, run %s", seed, run).isNull();
      check(result);
      if (wasOpen && !harness.state().isFullSyncInterrupted() && roundStart < run) {
        multiRunRounds++;
      }
    }

    /** A run the source refuses half way: it fails and removes nothing. */
    private void failingRun() {
      store.reset().budget(0).rejectCredentials();
      FileSyncHarness.Run result = harness.fullSync(store);
      store.acceptCredentials();
      assertThat(result.failure()).as("seed %s, run %s fails", seed, run).isNotNull();
      assertThat(result.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
    }

    /** An event run over one to three files that exist. */
    private void eventRun() {
      List<FileReference> references = new ArrayList<>();
      for (int i = 0; i < 1 + random.nextInt(3); i++) {
        String[] picked = pick();
        if (picked != null) {
          references.add(
              new FileReference(
                  new FileContainer(picked[0]), picked[1], store.filePathOf(picked[0], picked[1])));
        }
      }
      check(harness.refresh(store.reset().budget(0), references));
    }

    private void check(FileSyncHarness.Run result) {
      for (IndexingRunEvent event : result.eventsOf(IndexingEventCategory.REMOVED)) {
        String identity = event.getReference();
        removals++;
        if (byLocation) {
          assertThat(identities().contains(identity) && createdBefore.get(identity) <= roundStart)
              .as(
                  "seed %s, run %s: %s removed though it exists since the round began",
                  seed, run, identity)
              .isFalse();
        } else {
          assertThat(identities())
              .as("seed %s, run %s: removed although the file exists", seed, run)
              .doesNotContain(identity);
        }
      }
    }

    /** One to three changes at the source or the bestand between two runs. */
    private void change() {
      int changes = 1 + random.nextInt(3);
      for (int i = 0; i < changes; i++) {
        switch (random.nextInt(14)) {
          case 0, 1 -> add();
          case 2 -> rewrite();
          case 3 -> delete();
          case 4, 5 -> move();
          case 6 -> renameFolder();
          case 7 -> moveAcross();
          case 8 -> store.expireCheckpoints();
          case 9 -> forgetOne();
          case 10 -> toggleListing();
          case 11 -> toggleReading();
          case 12 -> failOrHealIngest();
          default -> add();
        }
      }
    }

    private void add() {
      String container = CONTAINERS.get(random.nextInt(CONTAINERS.size()));
      String name = freeName(container);
      store.put(container, name, "Inhalt " + (++version));
      live.get(container).add(name);
      createdBefore.put(store.filePathOf(container, name), run);
    }

    private void rewrite() {
      String[] picked = pick();
      if (picked != null) {
        store.put(picked[0], picked[1], "Neue, längere Fassung " + (++version));
      }
    }

    private void delete() {
      String[] picked = pick();
      if (picked != null) {
        store.remove(picked[0], picked[1]);
        live.get(picked[0]).remove(picked[1]);
      }
    }

    private void move() {
      String[] picked = pick();
      if (picked == null) {
        return;
      }
      String target = freeName(picked[0]);
      store.move(picked[0], picked[1], target);
      live.get(picked[0]).remove(picked[1]);
      live.get(picked[0]).add(target);
      if (byLocation) {
        createdBefore.put(store.filePathOf(picked[0], target), run);
      }
    }

    /** Renames the folder of a file, with everything below it, to a name nothing lives under. */
    private void renameFolder() {
      String[] picked = pick();
      if (picked == null || !picked[1].contains("/")) {
        return;
      }
      String container = picked[0];
      String folder = picked[1].substring(0, picked[1].lastIndexOf('/'));
      String target = "neu" + (++folderNames);
      store.move(container, folder, target);
      Set<String> names = live.get(container);
      for (String name : List.copyOf(names)) {
        if (name.startsWith(folder + "/")) {
          names.remove(name);
          String renamed = target + name.substring(folder.length());
          names.add(renamed);
          used.get(container).add(renamed);
          if (byLocation) {
            createdBefore.put(store.filePathOf(container, renamed), run);
          }
        }
      }
    }

    private void moveAcross() {
      String[] picked = pick();
      if (picked == null) {
        return;
      }
      String to = picked[0].equals("A") ? "B" : "A";
      if (live.get(to).contains(picked[1]) || (!byLocation && used.get(to).contains(picked[1]))) {
        return;
      }
      used.get(to).add(picked[1]);
      store.moveAcross(picked[0], picked[1], to);
      live.get(picked[0]).remove(picked[1]);
      live.get(to).add(picked[1]);
      createdBefore.put(store.filePathOf(to, picked[1]), run);
    }

    /** A document deleted by hand outside a run; it returns with a later run. */
    private void forgetOne() {
      List<String> stored = harness.storedPaths();
      if (!stored.isEmpty()) {
        harness.deleteStored(stored.get(random.nextInt(stored.size())));
      }
    }

    private void toggleListing() {
      String container = CONTAINERS.get(random.nextInt(CONTAINERS.size()));
      if (container.equals(lockedOutContainer)) {
        return;
      }
      if (deniedForAWhile.remove(container)) {
        store.allowListing(container);
      } else {
        deniedForAWhile.add(container);
        store.denyListing(container);
      }
    }

    private void toggleReading() {
      String container = CONTAINERS.get(random.nextInt(CONTAINERS.size()));
      if (unreadable.remove(container)) {
        store.allowReading(container);
      } else {
        unreadable.add(container);
        store.denyReading(container);
      }
    }

    private void failOrHealIngest() {
      String[] picked = pick();
      if (picked == null || random.nextBoolean()) {
        harness.healIngests();
      } else {
        harness.failIngestOf(store.filePathOf(picked[0], picked[1]));
      }
    }

    private String[] pick() {
      List<String[]> all = new ArrayList<>();
      live.forEach((container, names) -> names.forEach(n -> all.add(new String[] {container, n})));
      return all.isEmpty() ? null : all.get(random.nextInt(all.size()));
    }

    private String freeName(String container) {
      while (true) {
        String folder = FOLDERS.get(random.nextInt(FOLDERS.size()));
        String name =
            (folder.isEmpty() ? "" : folder + "/")
                + "f"
                + random.nextInt(byLocation ? 40 : 400)
                + ".txt";
        if (!live.get(container).contains(name)
            && (byLocation || !used.get(container).contains(name))) {
          used.get(container).add(name);
          return name;
        }
      }
    }

    private List<String> identitiesOf(String container) {
      return live.get(container).stream().map(name -> store.filePathOf(container, name)).toList();
    }

    private List<String> identities() {
      List<String> identities = new ArrayList<>();
      CONTAINERS.forEach(container -> identities.addAll(identitiesOf(container)));
      return identities;
    }
  }

  /** A system property, else the environment variable of the same name, else {@code fallback}. */
  private static int setting(String property, int fallback) {
    String value = System.getProperty(property);
    if (value == null) {
      value =
          System.getenv(
              property
                  .replace("opaa.filesync.", "OPAA_FILESYNC_")
                  .replaceAll("([a-z])([A-Z])", "$1_$2")
                  .toUpperCase(java.util.Locale.ROOT));
    }
    return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
  }
}
