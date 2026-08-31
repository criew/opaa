package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Docker-free unit tests for the Mehrfachlauf-Regel's aggregation math (issue #1044,
 * docs/features/retrieval-benchmark.md §3), run via {@code evalUnitTest}.
 */
class MultiRunAggregatorTest {

  private static final double TOLERANCE = 1e-9;

  private static PipelineEvaluationReport reportWithSubQueries(
      Map<String, List<String>> rankedFileNamesByQuery,
      Map<String, List<String>> subQueriesByQuery) {
    var goldenCases =
        List.of(
            new GoldenCase("a", "test", "frage a", List.of("a.md"), "cat", "easy", "de", "t", null),
            new GoldenCase(
                "b", "test", "frage b", List.of("b.md"), "cat", "easy", "de", "t", null));
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

  @Test
  void rejectsFewerThanTwoRuns() {
    assertThatThrownBy(() -> MultiRunAggregator.summarize(List.of(identicalRun(1))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void perMetricMinMedianMaxAreComputedAcrossRuns() {
    // Three runs, decreasing nDCG@8 as "frage a" stops hitting: run 0 and 1 hit both, run 2 misses
    // "a" — three distinct overall nDCG@8 values, so min < median < max is unambiguous.
    var runs = List.of(identicalRun(1), identicalRun(1), identicalRun(0));

    var summary = MultiRunAggregator.summarize(runs);

    assertThat(summary.runCount()).isEqualTo(3);
    assertThat(summary.ndcgAt8().max()).isCloseTo(1.0, within(TOLERANCE));
    assertThat(summary.ndcgAt8().min()).isLessThan(summary.ndcgAt8().max());
    assertThat(summary.ndcgAt8().median()).isEqualTo(summary.ndcgAt8().max());
    // The two identical, better runs tie for "the middle value" — medianRunIndex must point at one
    // of them (run 0 or run 1), never at the worse run 2.
    assertThat(summary.medianRunIndex()).isIn(0, 1);
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
                "a", "test", "frage a", List.of("a.md"), "cat", "easy", "de", "t", null));
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
