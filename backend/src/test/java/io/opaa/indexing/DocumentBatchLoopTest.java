package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The chargen loop the three resumable runs share (#1305). Pins the three behaviours their previous
 * copies had: a batch is bounded by its size, an unadvanceable candidate is scanned past rather
 * than reselected forever, and a call gives up after {@link DocumentBatchLoop#MAX_SKIP_SCAN_FACTOR}
 * times the batch size instead of scanning a whole corpus of unreachable documents.
 */
class DocumentBatchLoopTest {

  private enum Outcome {
    ADVANCED,
    MARKED,
    SKIPPED
  }

  @Test
  void advancesAtMostOneBatchAndCountsEveryOutcome() {
    List<UUID> corpus = ids(10);
    List<UUID> seen = new ArrayList<>();

    Map<Outcome, Integer> counts =
        DocumentBatchLoop.run(
            3,
            Outcome.class,
            Outcome.SKIPPED,
            (limit, offset) -> page(corpus, limit, offset),
            id -> {
              seen.add(id);
              return seen.size() % 2 == 0 ? Outcome.MARKED : Outcome.ADVANCED;
            });

    assertThat(seen).hasSize(3);
    assertThat(counts.get(Outcome.ADVANCED) + counts.get(Outcome.MARKED)).isEqualTo(3);
    assertThat(counts.get(Outcome.SKIPPED)).isZero();
  }

  @Test
  void scansPastASkippedCandidateInsteadOfReselectingIt() {
    List<UUID> corpus = ids(5);
    UUID unadvanceable = corpus.get(0);
    List<UUID> advanced = new ArrayList<>();

    Map<Outcome, Integer> counts =
        DocumentBatchLoop.run(
            2,
            Outcome.class,
            Outcome.SKIPPED,
            (limit, offset) -> page(corpus, limit, offset),
            id -> {
              if (id.equals(unadvanceable)) {
                return Outcome.SKIPPED;
              }
              advanced.add(id);
              return Outcome.ADVANCED;
            });

    assertThat(counts.get(Outcome.SKIPPED)).isEqualTo(1);
    assertThat(advanced).hasSize(2).doesNotContain(unadvanceable);
  }

  @Test
  void givesUpAfterTheSkipScanBoundAndDoesNothingForAnEmptyBatch() {
    List<UUID> corpus = ids(1000);

    Map<Outcome, Integer> counts =
        DocumentBatchLoop.run(
            2,
            Outcome.class,
            Outcome.SKIPPED,
            (limit, offset) -> page(corpus, limit, offset),
            id -> Outcome.SKIPPED);

    assertThat(counts.get(Outcome.SKIPPED)).isEqualTo(2 * DocumentBatchLoop.MAX_SKIP_SCAN_FACTOR);
    assertThat(counts.get(Outcome.ADVANCED)).isZero();

    assertThat(
            DocumentBatchLoop.run(
                0,
                Outcome.class,
                Outcome.SKIPPED,
                (limit, offset) -> {
                  throw new AssertionError("an empty batch selects nothing");
                },
                id -> Outcome.ADVANCED))
        .containsValues(0, 0, 0);
  }

  private static List<UUID> page(List<UUID> corpus, int limit, int offset) {
    if (offset >= corpus.size()) {
      return List.of();
    }
    return corpus.subList(offset, Math.min(corpus.size(), offset + limit));
  }

  private static List<UUID> ids(int count) {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      ids.add(UUID.randomUUID());
    }
    return ids;
  }
}
