package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Docker-free unit tests for the pipeline path's windowed metric math and aggregation (issue
 * #1039), run via the {@code evalUnitTest} Gradle task.
 *
 * <p>The expected values below are hand-computed at {@code k=20}, independently of the production
 * code, so a future change to the window or to the metric definitions fails here instead of
 * silently shifting a pipeline report.
 */
class PipelineMetricsAggregateTest {

  private static final double TOLERANCE = 1e-4;

  private static GoldenCase goldenCase(String id, String category, List<String> expected) {
    return new GoldenCase(
        id, "test", "query " + id, expected, category, "easy", "de", "t", null, null);
  }

  private static RetrievalMetrics.WindowedQueryResult at20(
      GoldenCase goldenCase, List<String> ranked) {
    return RetrievalMetrics.evaluateAt(
        goldenCase,
        ranked,
        PipelineMetricsAggregate.HIT_RATE_K,
        PipelineMetricsAggregate.RANKING_K);
  }

  @Test
  void windowIsTheProductionTopK() {
    assertThat(PipelineMetricsAggregate.RANKING_K).isEqualTo(20);
    assertThat(PipelineMetricsAggregate.HIT_RATE_K).isEqualTo(5);
  }

  @Test
  void hitAtRankOneScoresPerfectlyAtEveryMetric() {
    var result = at20(goldenCase("a", "c", List.of("e")), List.of("e", "d2", "d3"));

    assertThat(result.hitRateK()).isEqualTo(5);
    assertThat(result.rankingK()).isEqualTo(20);
    assertThat(result.hitRate()).isEqualTo(1.0);
    assertThat(result.reciprocalRank()).isEqualTo(1.0);
    assertThat(result.ndcg()).isCloseTo(1.0, within(TOLERANCE));
    assertThat(result.recall()).isEqualTo(1.0);
    assertThat(result.allExpectedDocumentsHit()).isEqualTo(1.0);
  }

  /**
   * A hit at document rank 21 lies outside this path's window, however the list came about. On the
   * pipeline path such a list cannot occur (the selection is capped at {@code top-k}).
   */
  @Test
  void hitBeyondRankTwentyIsOutsideTheWindow() {
    List<String> ranked = new ArrayList<>();
    for (int rank = 1; rank <= 20; rank++) {
      ranked.add("d" + rank);
    }
    ranked.add("e");

    var windowed = at20(goldenCase("a", "c", List.of("e")), ranked);
    assertThat(windowed.hitRate()).isEqualTo(0.0);
    assertThat(windowed.reciprocalRank()).isEqualTo(0.0);
    assertThat(windowed.ndcg()).isEqualTo(0.0);
    assertThat(windowed.recall()).isEqualTo(0.0);
  }

  /**
   * The characteristic difference to the raw-vector path: a hit at document rank 15 counts here
   * ({@code k=20}) but lies outside that path's {@code k=10} window, which is why the two paths'
   * numbers are not interconvertible.
   */
  @Test
  void hitAtRankFifteenCountsHereButNotOnTheRawVectorPath() {
    List<String> ranked = new ArrayList<>();
    for (int rank = 1; rank <= 14; rank++) {
      ranked.add("d" + rank);
    }
    ranked.add("e");
    GoldenCase goldenCase = goldenCase("a", "c", List.of("e"));

    var windowed = at20(goldenCase, ranked);
    assertThat(windowed.hitRate()).isEqualTo(0.0);
    assertThat(windowed.reciprocalRank()).isCloseTo(1.0 / 15, within(TOLERANCE));
    assertThat(windowed.recall()).isEqualTo(1.0);
    // DCG = 1/log2(16) = 0.25; IDCG for one expected = 1.
    assertThat(windowed.ndcg()).isCloseTo(0.25, within(TOLERANCE));

    var rawVector = RetrievalMetrics.evaluate(goldenCase, ranked);
    assertThat(rawVector.recallAt10()).isEqualTo(0.0);
  }

  @Test
  void partialRecallOfAMultiDocumentCase() {
    var result = at20(goldenCase("a", "c", List.of("e1", "e2")), List.of("d1", "e1", "d3"));

    assertThat(result.hitRate()).isEqualTo(1.0);
    assertThat(result.reciprocalRank()).isCloseTo(0.5, within(TOLERANCE));
    assertThat(result.recall()).isCloseTo(0.5, within(TOLERANCE));
    // "Recall pro Teilthema": one of two expected documents is not enough.
    assertThat(result.allExpectedDocumentsHit()).isEqualTo(0.0);
    // DCG = 1/log2(3) = 0.6309; IDCG for two expected = 1 + 1/log2(3) = 1.6309.
    assertThat(result.ndcg()).isCloseTo(0.6309 / 1.6309, within(TOLERANCE));
  }

  @Test
  void emptySelectionScoresZeroRatherThanFailing() {
    var result = at20(goldenCase("a", "c", List.of("e")), List.of());

    assertThat(result.hitRate()).isEqualTo(0.0);
    assertThat(result.reciprocalRank()).isEqualTo(0.0);
    assertThat(result.ndcg()).isEqualTo(0.0);
    assertThat(result.recall()).isEqualTo(0.0);
  }

  @Test
  void aggregatesAsAMicroMeanOverCases() {
    var hit = at20(goldenCase("a", "c1", List.of("e")), List.of("e"));
    var miss = at20(goldenCase("b", "c1", List.of("x")), List.of("e"));

    PipelineMetricsAggregate aggregate = PipelineMetricsAggregate.of(List.of(hit, miss));

    assertThat(aggregate.n()).isEqualTo(2);
    assertThat(aggregate.hitRateAt5()).isCloseTo(0.5, within(TOLERANCE));
    assertThat(aggregate.mrrAt20()).isCloseTo(0.5, within(TOLERANCE));
    assertThat(aggregate.ndcgAt20()).isCloseTo(0.5, within(TOLERANCE));
    assertThat(aggregate.recallAt20()).isCloseTo(0.5, within(TOLERANCE));
    assertThat(aggregate.hitCountAt5()).isEqualTo(1);
    assertThat(aggregate.hitCountAt20()).isEqualTo(1);
    assertThat(aggregate.distinctExpectedDocumentSets()).isEqualTo(2);
  }

  @Test
  void recallCeilingReflectsCasesExpectingMoreThanTwentyDocuments() {
    List<String> twentyFourExpected = new ArrayList<>();
    for (int document = 1; document <= 24; document++) {
      twentyFourExpected.add(String.valueOf(document));
    }
    PipelineMetricsAggregate aggregate =
        PipelineMetricsAggregate.of(
            List.of(at20(goldenCase("a", "c", twentyFourExpected), List.of())));

    assertThat(aggregate.recallAt20Ceiling()).isCloseTo(20.0 / 24.0, within(TOLERANCE));
  }

  /**
   * The ceiling is the structural bound of the window, never a property of what a run happened to
   * return: a ceiling derived from the measurement could never be missed, so a run whose threshold
   * filtered nearly everything away would report Recall "at the maximum achievable".
   */
  @Test
  void recallCeilingIsStructuralAndUnaffectedByWhatTheRunReturned() {
    GoldenCase goldenCase = goldenCase("a", "c", List.of("1", "2", "3", "4", "5", "6"));

    double withEightDocumentsReturned =
        PipelineMetricsAggregate.of(
                List.of(at20(goldenCase, List.of("1", "2", "3", "4", "5", "6", "d7", "d8"))))
            .recallAt20Ceiling();
    double withNothingReturned =
        PipelineMetricsAggregate.of(List.of(at20(goldenCase, List.of()))).recallAt20Ceiling();

    assertThat(withEightDocumentsReturned).isEqualTo(1.0);
    assertThat(withNothingReturned).isEqualTo(withEightDocumentsReturned);
  }

  @Test
  void groupsByAnArbitraryKey() {
    var a = at20(goldenCase("a", "alpha", List.of("e")), List.of("e"));
    var b = at20(goldenCase("b", "beta", List.of("e")), List.of("x"));

    var grouped = PipelineMetricsAggregate.groupBy(List.of(a, b), GoldenCase::category);

    assertThat(grouped).containsOnlyKeys("alpha", "beta");
    assertThat(grouped.get("alpha").hitRateAt5()).isEqualTo(1.0);
    assertThat(grouped.get("beta").hitRateAt5()).isEqualTo(0.0);
  }

  /** The component names promise a window; a result measured at another one must not slip in. */
  @Test
  void refusesResultsMeasuredAtAnotherWindow() {
    var atTen =
        RetrievalMetrics.evaluateAt(goldenCase("a", "c", List.of("e")), List.of("e"), 5, 10);

    assertThatThrownBy(() -> PipelineMetricsAggregate.of(List.of(atTen)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rankingK=10");
  }
}
