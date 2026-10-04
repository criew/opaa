package io.opaa.indexing.filesync;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingRunEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Full syncs over several runs against the reference store while files are added, changed, moved
 * and deleted between the runs, under a budget that changes from run to run and with one to three
 * downloads in flight - with a fixed start value per scenario, named in every failure. No document
 * is removed whose file exists under the same identity; for a store whose identity is the location,
 * under the same identity since the round began. Once the source stays still, the bestand matches
 * it. The environment variables {@code OPAA_FILESYNC_RANDOM_SEEDS}, {@code _RUNS} and {@code
 * _FIRST_SEED} widen the search.
 */
class FileSyncRandomizedRoundTest {

  private static final int SEEDS = setting("OPAA_FILESYNC_RANDOM_SEEDS", 12);
  private static final int RUNS = setting("OPAA_FILESYNC_RANDOM_RUNS", 40);
  private static final long FIRST_SEED = setting("OPAA_FILESYNC_RANDOM_FIRST_SEED", 20261004);

  private static final List<String> FOLDERS = List.of("", "a", "b", "c", "a/x", "b/y", "c/z/w");
  private static final List<String> CONTAINERS = List.of("A", "B");

  private int multiRunRounds;
  private int removals;

  @Test
  void aStoreTrackedByIdentityRemovesNothingThatStillExists() throws Exception {
    for (long seed = FIRST_SEED; seed < FIRST_SEED + SEEDS; seed++) {
      new Scenario(seed, false).play();
    }
    assertThat(multiRunRounds).as("rounds over several runs were exercised").isPositive();
    assertThat(removals).as("removals were exercised").isPositive();
  }

  @Test
  void aStoreIdentifiedByLocationRemovesNothingThatExistedSinceTheRoundBegan() throws Exception {
    for (long seed = FIRST_SEED; seed < FIRST_SEED + SEEDS; seed++) {
      new Scenario(seed, true).play();
    }
    assertThat(multiRunRounds).as("rounds over several runs were exercised").isPositive();
    assertThat(removals).as("removals were exercised").isPositive();
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

    /** Per identity, the run before which it was last created. */
    private final Map<String, Integer> createdBefore = new HashMap<>();

    private int run;
    private int roundStart;
    private int version;

    private Scenario(long seed, boolean byLocation) throws Exception {
      this.seed = seed;
      this.byLocation = byLocation;
      this.random = new Random(seed);
      this.store = new InMemoryFileStore().withCheckpoints().pageSize(1 + random.nextInt(3));
      if (byLocation) {
        store.absenceProof(AbsenceProof.LOCATION_IDENTITY);
      } else {
        store.withFolderMarkers().withStableIds();
      }
      this.harness = new FileSyncHarness().downloadConcurrency(1 + random.nextInt(3));
      CONTAINERS.forEach(
          container -> {
            store.container(container);
            live.put(container, new LinkedHashSet<>());
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
        runOnce(random.nextInt(5) == 0 ? 0 : 3 + random.nextInt(25));
      }
      for (int quiet = 0; quiet < 4; quiet++, run++) {
        runOnce(0);
      }
      assertThat(harness.storedPaths())
          .as("seed %s: once the source stays still, the bestand matches it", seed)
          .containsExactlyInAnyOrderElementsOf(identities());
    }

    private void runOnce(int budget) {
      if (harness.state().scanProgress() == null) {
        roundStart = run;
      }
      boolean wasOpen = harness.state().isFullSyncInterrupted();
      FileSyncHarness.Run result = harness.fullSync(store.reset().budget(budget));
      assertThat(result.failure()).as("seed %s, run %s", seed, run).isNull();
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
      if (wasOpen && !harness.state().isFullSyncInterrupted() && roundStart < run) {
        multiRunRounds++;
      }
    }

    /** One to three changes at the source between two runs. */
    private void change() {
      int changes = 1 + random.nextInt(3);
      for (int i = 0; i < changes; i++) {
        switch (random.nextInt(7)) {
          case 0, 1 -> add();
          case 2 -> rewrite();
          case 3 -> delete();
          case 4, 5 -> move();
          default -> {
            if (random.nextBoolean()) {
              store.expireCheckpoints();
            } else {
              forgetOne();
            }
          }
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

    /** A document deleted by hand outside a run; it returns with a later run. */
    private void forgetOne() {
      List<String> stored = harness.storedPaths();
      if (!stored.isEmpty()) {
        harness.deleteStored(stored.get(random.nextInt(stored.size())));
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
        String name = (folder.isEmpty() ? "" : folder + "/") + "f" + random.nextInt(40) + ".txt";
        if (!live.get(container).contains(name)) {
          return name;
        }
      }
    }

    private List<String> identities() {
      List<String> identities = new ArrayList<>();
      live.forEach(
          (container, names) ->
              names.forEach(name -> identities.add(store.filePathOf(container, name))));
      return identities;
    }
  }

  private static int setting(String name, int fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
  }
}
