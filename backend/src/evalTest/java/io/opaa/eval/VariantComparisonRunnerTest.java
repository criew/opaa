package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Docker-free unit tests for {@link VariantComparisonRunner#delta}, the paired per-case and
 * aggregated delta computation at the heart of the variant mechanism (issue #1041). {@link
 * VariantComparisonRunner#run} itself needs a real {@code QueryService} via {@link VariantRunner}
 * and is exercised end to end only by the Docker-requiring {@code RetrievalEvaluationHarnessTest}.
 */
class VariantComparisonRunnerTest {

  private static final double TOLERANCE = 1e-9;

  static PipelineEvaluationReport.PipelineRunConfiguration runConfiguration() {
    return new PipelineEvaluationReport.PipelineRunConfiguration(
        "test-domain",
        "ollama",
        "model",
        "digest",
        "image",
        768,
        1000,
        true,
        100,
        25,
        8,
        0.3,
        "angewandt",
        2,
        1.0,
        false,
        3,
        null,
        5,
        8,
        "hnsw",
        "manifest",
        1,
        "eval/golden/test.json",
        "golden",
        1,
        1,
        PipelineHarnessSupport.SEARCH_SCOPE_NOTE,
        "2026-08-31T00:00:00Z",
        1.0);
  }

  private static PipelineVariant variant(String name) {
    return new PipelineVariant(name, "desc", false, PipelineVariant.QueryOverrides.NONE);
  }

  private static PipelineEvaluationReport report(Map<String, List<String>> pipeline) {
    var goldenCases =
        List.of(
            new GoldenCase("a", "test", "frage a", List.of("a.md"), "cat", "easy", "de", "t", null),
            new GoldenCase(
                "b", "test", "frage b", List.of("b.md"), "cat", "easy", "de", "t", null));
    return PipelineRetrievalEvaluator.report(
        PipelineRetrievalEvaluator.evaluateAll(goldenCases, pipeline::get), runConfiguration());
  }

  @Test
  void aVariantThatFindsEverythingHasAPositiveDeltaAgainstAReferenceThatFindsNothing() {
    var reference =
        VariantOutcome.executed(
            variant("reference"), report(Map.of("frage a", List.of(), "frage b", List.of())));
    var better =
        VariantOutcome.executed(
            variant("better"),
            report(Map.of("frage a", List.of("a.md"), "frage b", List.of("b.md"))));

    var comparison = VariantComparisonRunner.delta(better, reference);

    assertThat(comparison.variantName()).isEqualTo("better");
    assertThat(comparison.aggregateDelta().hitRateAt5Delta()).isCloseTo(1.0, within(TOLERANCE));
    assertThat(comparison.aggregateDelta().ndcgAt8Delta()).isCloseTo(1.0, within(TOLERANCE));
    assertThat(comparison.caseDeltas()).hasSize(2);
    assertThat(comparison.caseDeltas())
        .allSatisfy(d -> assertThat(d.ndcgAt8Delta()).isCloseTo(1.0, within(TOLERANCE)));
  }

  @Test
  void anIdenticalVariantHasAllZeroDeltas() {
    Map<String, List<String>> pipeline =
        Map.of("frage a", List.of("a.md"), "frage b", List.of("b.md"));
    var reference = VariantOutcome.executed(variant("reference"), report(pipeline));
    var same = VariantOutcome.executed(variant("same"), report(pipeline));

    var comparison = VariantComparisonRunner.delta(same, reference);

    assertThat(comparison.aggregateDelta().hitRateAt5Delta()).isZero();
    assertThat(comparison.aggregateDelta().mrrAt8Delta()).isZero();
    assertThat(comparison.aggregateDelta().ndcgAt8Delta()).isZero();
    assertThat(comparison.aggregateDelta().recallAt8Delta()).isZero();
    assertThat(comparison.caseDeltas()).allSatisfy(d -> assertThat(d.ndcgAt8Delta()).isZero());
  }

  @Test
  void caseDeltasAreSortedWorstFirstByNdcgDelta() {
    var reference =
        VariantOutcome.executed(
            variant("reference"),
            report(Map.of("frage a", List.of("a.md"), "frage b", List.of("b.md"))));
    // "a" regresses (found -> not found), "b" is unchanged.
    var worse =
        VariantOutcome.executed(
            variant("worse"), report(Map.of("frage a", List.of(), "frage b", List.of("b.md"))));

    var comparison = VariantComparisonRunner.delta(worse, reference);

    assertThat(comparison.caseDeltas().get(0).caseId()).isEqualTo("a");
    assertThat(comparison.caseDeltas().get(0).ndcgAt8Delta()).isLessThan(0.0);
  }
}
