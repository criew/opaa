package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Docker-free unit tests for the Mehrfachlauf-Regel's aggregation math (issue #1044,
 * docs/features/retrieval-benchmark.md §3), run via {@code evalUnitTest}.
 */
class MultiRunAggregatorTest {

  private static PipelineEvaluationReport reportWithSubQueries(
      Map<String, List<String>> rankedFileNamesByQuery,
      Map<String, List<String>> subQueriesByQuery) {
    var goldenCases =
        List.of(
            new GoldenCase(
                "a",
                "test",
                "frage a",
                List.of("a.md"),
                "cat",
                "easy",
                "de",
                "t",
                null,
                null,
                null,
                null,
                null),
            new GoldenCase(
                "b",
                "test",
                "frage b",
                List.of("b.md"),
                "cat",
                "easy",
                "de",
                "t",
                null,
                null,
                null,
                null,
                null));
    return PipelineRetrievalEvaluator.report(
        PipelineRetrievalEvaluator.evaluateAll(
            goldenCases,
            query ->
                new PipelineRetrievalEvaluator.PipelineInvocationResult(
                    rankedFileNamesByQuery.get(query), subQueriesByQuery.get(query))),
        VariantComparisonRunnerTest.runConfiguration());
  }

  private static PipelineEvaluationReport identicalRun(int hitsForA) {
    return reportWithSubQueries(
        Map.of("frage a", hitsForA > 0 ? List.of("a.md") : List.of(), "frage b", List.of("b.md")),
        Map.of(
            "frage a", List.of("frage a: unterfrage"),
            "frage b", List.of("frage b: unterfrage")));
  }

  /**
   * A run where "frage a" hits at a chosen rank ({@code 1}, {@code 3}, or a miss) while "frage b"
   * always hits at rank 1. Unlike {@link #identicalRun}, this varies MRR@8 and nDCG@8 independently
   * of Hit Rate@5/Recall@8 (both stay 1.0 for a rank-1 and a rank-3 hit — the expected document is
   * still within the top-5/top-8 window either way) — issue #1044 review, Befund 3: a metric
   * swapped for another in the aggregation loop below would otherwise go unnoticed as long as it
   * swapped two metrics that happened to carry the same value.
   */
  private static PipelineEvaluationReport runWithCaseAHitAtRank(String rank) {
    List<String> rankedForA =
        switch (rank) {
          case "1" -> List.of("a.md");
          case "3" -> List.of("x.md", "y.md", "a.md");
          default -> List.of();
        };
    return reportWithSubQueries(
        Map.of("frage a", rankedForA, "frage b", List.of("b.md")),
        Map.of(
            "frage a", List.of("frage a: unterfrage"),
            "frage b", List.of("frage b: unterfrage")));
  }

  @Test
  void rejectsFewerThanTwoRuns() {
    assertThatThrownBy(() -> MultiRunAggregator.summarize(List.of(identicalRun(1))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void perMetricMinMedianMaxAreComputedAcrossRuns() {
    // Three runs with three genuinely distinct MRR@8/nDCG@8 values: run 0 hits "frage a" at rank
    // 1 (best), run 1 at rank 3 (worse but still a hit), run 2 misses it entirely (worst) — "frage
    // b" hits at rank 1 in every run, so Hit Rate@5/Recall@8 stay at their ceiling for run 0/1 and
    // only nDCG@8/MRR@8 actually order the three runs (see runWithCaseAHitAtRank's Javadoc).
    var runs =
        List.of(
            runWithCaseAHitAtRank("1"), runWithCaseAHitAtRank("3"), runWithCaseAHitAtRank("miss"));

    var summary = MultiRunAggregator.summarize(runs);

    assertThat(summary.runCount()).isEqualTo(3);
    assertThat(summary.ndcgAt8().min()).isLessThan(summary.ndcgAt8().median());
    assertThat(summary.ndcgAt8().median()).isLessThan(summary.ndcgAt8().max());
    // Sorted by nDCG@8 ascending, the middle value is run 1 (rank-3 hit) — neither the best (run 0)
    // nor the worst (run 2) run.
    assertThat(summary.medianRunIndex()).isEqualTo(1);
  }

  @Test
  void aCaseWhoseSubQueriesDifferAcrossRunsIsCountedAsDeviating() {
    var runA =
        reportWithSubQueries(
            Map.of("frage a", List.of("a.md"), "frage b", List.of("b.md")),
            Map.of(
                "frage a", List.of("teilfrage 1", "teilfrage 2"),
                "frage b", List.of("teilfrage x")));
    var runB =
        reportWithSubQueries(
            Map.of("frage a", List.of("a.md"), "frage b", List.of("b.md")),
            Map.of(
                // "frage a" got a different decomposition this run; "frage b" did not.
                "frage a", List.of("andere teilfrage"),
                "frage b", List.of("teilfrage x")));

    var summary = MultiRunAggregator.summarize(List.of(runA, runB));

    assertThat(summary.decompositionDeviatingCaseCount()).isEqualTo(1);
    assertThat(summary.decompositionDeviatingCaseIds()).containsExactly("a");
  }

  @Test
  void identicalDecompositionAcrossRunsCountsNoDeviation() {
    var runs = List.of(identicalRun(1), identicalRun(1), identicalRun(1));

    var summary = MultiRunAggregator.summarize(runs);

    assertThat(summary.decompositionDeviatingCaseCount()).isZero();
    assertThat(summary.decompositionDeviatingCaseIds()).isEmpty();
  }

  @Test
  void aCaseMissingFromASubsequentRunFailsLoudly() {
    var runA =
        reportWithSubQueries(
            Map.of("frage a", List.of("a.md"), "frage b", List.of("b.md")),
            Map.of("frage a", List.of("t"), "frage b", List.of("t")));
    var goldenCasesRunB =
        List.of(
            new GoldenCase(
                "a",
                "test",
                "frage a",
                List.of("a.md"),
                "cat",
                "easy",
                "de",
                "t",
                null,
                null,
                null,
                null,
                null));
    var runB =
        PipelineRetrievalEvaluator.report(
            PipelineRetrievalEvaluator.evaluateAll(
                goldenCasesRunB,
                query ->
                    new PipelineRetrievalEvaluator.PipelineInvocationResult(
                        List.of("a.md"), List.of("t"))),
            VariantComparisonRunnerTest.runConfiguration());

    assertThatThrownBy(() -> MultiRunAggregator.summarize(List.of(runA, runB)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("'b'");
  }
}
