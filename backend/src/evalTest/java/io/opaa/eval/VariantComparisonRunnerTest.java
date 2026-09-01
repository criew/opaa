package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        true,
        true,
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
        1.0,
        false);
  }

  private static PipelineVariant variant(String name) {
    return new PipelineVariant(name, "desc", false, PipelineVariant.QueryOverrides.NONE);
  }

  private static PipelineEvaluationReport report(Map<String, List<String>> pipeline) {
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
        PipelineRetrievalEvaluator.evaluateAll(goldenCases, toPipeline(pipeline)),
        runConfiguration());
  }

  /**
   * Wraps a {@code query -> ranked file names} map into the {@code Function<String,
   * PipelineInvocationResult>} {@link PipelineRetrievalEvaluator#evaluateAll} expects, using the
   * query itself as its own single-entry sub-query list — these tests are not exercising
   * decomposition, so any deterministic, non-null sub-query list is sufficient.
   */
  static java.util.function.Function<String, PipelineRetrievalEvaluator.PipelineInvocationResult>
      toPipeline(Map<String, List<String>> pipeline) {
    return query ->
        new PipelineRetrievalEvaluator.PipelineInvocationResult(
            pipeline.get(query), List.of(query));
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

  /**
   * Issue #1041 review, Befund 7: a variant that (through a bug elsewhere, since {@link
   * VariantRunner} always passes the same golden cases to every variant) evaluated a case the
   * reference variant never saw must fail loudly — "gepaarte Messung" is the mechanism's whole
   * premise, and silently skipping the stray case would hide exactly that breakage.
   */
  @Test
  void aCaseMissingFromTheReferenceFailsLoudly() {
    var reference =
        VariantOutcome.executed(
            variant("reference"),
            report(Map.of("frage a", List.of("a.md"), "frage b", List.of("b.md"))));
    var strayCase =
        new GoldenCase(
            "c",
            "test",
            "frage c",
            List.of("c.md"),
            "cat",
            "easy",
            "de",
            "t",
            null,
            null,
            null,
            null,
            null);
    PipelineEvaluationReport strayReport =
        PipelineRetrievalEvaluator.report(
            PipelineRetrievalEvaluator.evaluateAll(
                List.of(strayCase), toPipeline(Map.of("frage c", List.of("c.md")))),
            runConfiguration());
    var stray = VariantOutcome.executed(variant("stray"), strayReport);

    assertThatThrownBy(() -> VariantComparisonRunner.delta(stray, reference))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("stray")
        .hasMessageContaining("'c'");
  }
}
