package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Docker-free unit tests for the pure ranking-metric math (issue #227 review follow-up). Run via
 * the {@code evalUnitTest} Gradle task (wired into {@code check}) — deliberately not the
 * Docker-requiring {@code evaluateRetrieval} task, which only runs {@code
 * RetrievalEvaluationHarnessTest}.
 *
 * <p>These cases were hand-computed independently of {@link RetrievalMetrics} and cross-checked
 * against a full harness run before being pinned here, so a future change to the gain function,
 * IDCG basis or rank counting fails loudly instead of silently drifting the baseline.
 */
class RetrievalMetricsTest {

  private static final double TOLERANCE = 1e-4;

  private static GoldenCase caseWithExpected(List<String> expected) {
    return new GoldenCase(
        "t",
        "test",
        "query",
        expected,
        "category",
        "difficulty",
        "en",
        "type",
        null,
        null,
        null,
        null);
  }

  @Test
  void singleExpectedDocumentHitAtRankOne() {
    GoldenCase goldenCase = caseWithExpected(List.of("e"));
    RetrievalMetrics.QueryResult result =
        RetrievalMetrics.evaluate(goldenCase, List.of("e", "d2", "d3", "d4", "d5"));

    assertThat(result.hitRateAt5()).isEqualTo(1.0);
    assertThat(result.reciprocalRank()).isEqualTo(1.0);
    assertThat(result.ndcgAt10()).isCloseTo(1.0, within(TOLERANCE));
    assertThat(result.recallAt10()).isEqualTo(1.0);
  }

  @Test
  void singleExpectedDocumentHitAtRankThree() {
    GoldenCase goldenCase = caseWithExpected(List.of("e"));
    RetrievalMetrics.QueryResult result =
        RetrievalMetrics.evaluate(goldenCase, List.of("d1", "d2", "e", "d4", "d5"));

    assertThat(result.hitRateAt5()).isEqualTo(1.0);
    assertThat(result.reciprocalRank()).isCloseTo(1.0 / 3, within(TOLERANCE));
    assertThat(result.ndcgAt10()).isCloseTo(0.5, within(TOLERANCE));
    assertThat(result.recallAt10()).isEqualTo(1.0);
  }

  @Test
  void singleExpectedDocumentHitAtRankSixFallsOutsideHitRateWindow() {
    GoldenCase goldenCase = caseWithExpected(List.of("e"));
    RetrievalMetrics.QueryResult result =
        RetrievalMetrics.evaluate(
            goldenCase, List.of("d1", "d2", "d3", "d4", "d5", "e", "d7", "d8", "d9", "d10"));

    // Hit Rate@5 only looks at the top 5 — rank 6 does not count.
    assertThat(result.hitRateAt5()).isEqualTo(0.0);
    assertThat(result.reciprocalRank()).isCloseTo(1.0 / 6, within(TOLERANCE));
    assertThat(result.ndcgAt10()).isCloseTo(0.3562, within(1e-3));
    // Recall@10 looks at the top 10 — rank 6 does count.
    assertThat(result.recallAt10()).isEqualTo(1.0);
  }

  @Test
  void twoExpectedDocumentsHitAtRanksOneAndFour() {
    GoldenCase goldenCase = caseWithExpected(List.of("e1", "e2"));
    RetrievalMetrics.QueryResult result =
        RetrievalMetrics.evaluate(goldenCase, List.of("e1", "d2", "d3", "e2", "d5"));

    assertThat(result.hitRateAt5()).isEqualTo(1.0);
    assertThat(result.reciprocalRank()).isEqualTo(1.0);
    assertThat(result.ndcgAt10()).isCloseTo(0.8772, within(1e-3));
    assertThat(result.recallAt10()).isEqualTo(1.0);
    assertThat(result.allExpectedDocumentsHitAt10()).isEqualTo(1.0);
  }

  @Test
  void allExpectedDocumentsHitAt10RequiresEveryExpectedDocumentUnlikePartialCreditRecall() {
    // Issue #913 ("Recall pro Teilthema"): recallAt10 gives 0.5 partial credit for one of two
    // expected documents retrieved — exactly the shape that would silently hide a topK-monoculture
    // regression (issue #912). allExpectedDocumentsHitAt10 must score this case as a miss (0.0).
    GoldenCase goldenCase = caseWithExpected(List.of("e1", "e2"));
    RetrievalMetrics.QueryResult result =
        RetrievalMetrics.evaluate(goldenCase, List.of("e1", "d2", "d3", "d4", "d5"));

    assertThat(result.recallAt10()).isEqualTo(0.5);
    assertThat(result.allExpectedDocumentsHitAt10()).isEqualTo(0.0);
  }

  @Test
  void allExpectedDocumentsHitAt10IsOneOnlyWhenBothExpectedDocumentsAreInTheTop10Window() {
    GoldenCase goldenCase = caseWithExpected(List.of("e1", "e2"));
    // e2 lands at rank 9 — inside the top-10 recall window this metric shares with recallAt10, but
    // outside the top-5 hitRateAt5 window.
    RetrievalMetrics.QueryResult result =
        RetrievalMetrics.evaluate(
            goldenCase, List.of("e1", "d2", "d3", "d4", "d5", "d6", "d7", "d8", "e2", "d10"));

    assertThat(result.allExpectedDocumentsHitAt10()).isEqualTo(1.0);
  }

  @Test
  void allExpectedDocumentsHitAt10IsZeroWhenAnExpectedDocumentLandsJustOutsideTheTop10Window() {
    // Issue #913 review, Nit 2: pins the k=10 cutoff itself — without it, removing the subList
    // truncation in allExpectedDocumentsHitAtK would still leave this suite green.
    GoldenCase goldenCase = caseWithExpected(List.of("e1", "e2"));
    RetrievalMetrics.QueryResult result =
        RetrievalMetrics.evaluate(
            goldenCase, List.of("e1", "d2", "d3", "d4", "d5", "d6", "d7", "d8", "d9", "d10", "e2"));

    assertThat(result.allExpectedDocumentsHitAt10()).isEqualTo(0.0);
  }

  @Test
  void twelveExpectedDocumentsHitAtRanksOneTwoFiveNine() {
    GoldenCase goldenCase =
        caseWithExpected(
            List.of("e1", "e2", "e3", "e4", "e5", "e6", "e7", "e8", "e9", "e10", "e11", "e12"));
    List<String> ranked = List.of("e1", "e2", "d3", "d4", "e3", "d6", "d7", "d8", "e4", "d10");
    RetrievalMetrics.QueryResult result = RetrievalMetrics.evaluate(goldenCase, ranked);

    assertThat(result.hitRateAt5()).isEqualTo(1.0);
    assertThat(result.reciprocalRank()).isEqualTo(1.0);
    assertThat(result.ndcgAt10()).isCloseTo(0.5104, within(1e-3));
    assertThat(result.recallAt10()).isCloseTo(1.0 / 3, within(TOLERANCE));
  }

  @Test
  void fifteenExpectedDocumentsAllTenTopRanksRelevant_pinsIdcgBasisAgainstRegression() {
    GoldenCase goldenCase =
        caseWithExpected(
            List.of(
                "e1", "e2", "e3", "e4", "e5", "e6", "e7", "e8", "e9", "e10", "e11", "e12", "e13",
                "e14", "e15"));
    List<String> ranked = List.of("e1", "e2", "e3", "e4", "e5", "e6", "e7", "e8", "e9", "e10");
    RetrievalMetrics.QueryResult result = RetrievalMetrics.evaluate(goldenCase, ranked);

    assertThat(result.hitRateAt5()).isEqualTo(1.0);
    assertThat(result.reciprocalRank()).isEqualTo(1.0);
    // With |expected| > k, a perfect top-k ranking must still normalize to exactly 1.0 — this is
    // what pins the IDCG basis to Math.min(k, expected.size()) instead of expected.size() alone.
    assertThat(result.ndcgAt10()).isCloseTo(1.0, within(TOLERANCE));
    assertThat(result.recallAt10()).isCloseTo(10.0 / 15, within(TOLERANCE));
  }

  @Test
  void noExpectedDocumentsProducesZeroMetricsInsteadOfDividingByZero() {
    GoldenCase goldenCase = caseWithExpected(List.of());
    RetrievalMetrics.QueryResult result = RetrievalMetrics.evaluate(goldenCase, List.of("d1"));

    assertThat(result.hitRateAt5()).isEqualTo(0.0);
    assertThat(result.reciprocalRank()).isEqualTo(0.0);
    assertThat(result.ndcgAt10()).isEqualTo(0.0);
    assertThat(result.recallAt10()).isEqualTo(0.0);
    assertThat(result.allExpectedDocumentsHitAt10()).isEqualTo(0.0);
  }

  @Test
  void noHitsAnywhereInRankedListProducesZeroMetrics() {
    GoldenCase goldenCase = caseWithExpected(List.of("e"));
    RetrievalMetrics.QueryResult result =
        RetrievalMetrics.evaluate(goldenCase, List.of("d1", "d2", "d3"));

    assertThat(result.hitRateAt5()).isEqualTo(0.0);
    assertThat(result.reciprocalRank()).isEqualTo(0.0);
    assertThat(result.ndcgAt10()).isEqualTo(0.0);
    assertThat(result.recallAt10()).isEqualTo(0.0);
  }

  @Test
  void recallCeilingIsBelowOneWhenExpectedSetExceedsK() {
    assertThat(
            RetrievalMetrics.recallCeilingAtK(
                java.util.Set.of(
                    "e1", "e2", "e3", "e4", "e5", "e6", "e7", "e8", "e9", "e10", "e11", "e12",
                    "e13", "e14", "e15"),
                10))
        .isCloseTo(10.0 / 15, within(TOLERANCE));
  }

  @Test
  void recallCeilingIsOneWhenExpectedSetFitsWithinK() {
    assertThat(RetrievalMetrics.recallCeilingAtK(java.util.Set.of("e1", "e2"), 10)).isEqualTo(1.0);
  }
}
