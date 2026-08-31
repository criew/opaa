package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VariantOutcomeTest {

  private static PipelineVariant variant() {
    return new PipelineVariant("v", "desc", false, PipelineVariant.QueryOverrides.NONE);
  }

  private static PipelineEvaluationReport report() {
    return PipelineRetrievalEvaluator.report(
        PipelineRetrievalEvaluator.evaluateAll(
            List.of(
                new GoldenCase(
                    "a",
                    "test",
                    "frage",
                    List.of("a.md"),
                    "cat",
                    "easy",
                    "de",
                    "t",
                    null,
                    null,
                    null,
                    null,
                    null)),
            VariantComparisonRunnerTest.toPipeline(Map.of("frage", List.of("a.md")))),
        VariantComparisonRunnerTest.runConfiguration());
  }

  @Test
  void executedCarriesAReportAndNoSkipReason() {
    var outcome = VariantOutcome.executed(variant(), report());

    assertThat(outcome.executed()).isTrue();
    assertThat(outcome.report()).isNotNull();
    assertThat(outcome.skipReason()).isNull();
  }

  @Test
  void skippedCarriesAReasonAndNoReport() {
    var outcome = VariantOutcome.skipped(variant(), "requires reindex");

    assertThat(outcome.executed()).isFalse();
    assertThat(outcome.report()).isNull();
    assertThat(outcome.skipReason()).isEqualTo("requires reindex");
  }

  @Test
  void anExecutedOutcomeMustNotCarryASkipReason() {
    assertThatThrownBy(() -> new VariantOutcome(variant(), true, "reason", report(), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aSkippedOutcomeMustCarryANonBlankReason() {
    assertThatThrownBy(() -> new VariantOutcome(variant(), false, " ", null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aSkippedOutcomeMustNotCarryAMultiRunSummary() {
    var summary =
        new MultiRunSummary(
            3,
            new MultiRunSummary.MetricRange(0, 0, 0),
            new MultiRunSummary.MetricRange(0, 0, 0),
            new MultiRunSummary.MetricRange(0, 0, 0),
            new MultiRunSummary.MetricRange(0, 0, 0),
            0,
            0,
            List.of());

    assertThatThrownBy(
            () -> new VariantOutcome(variant(), false, "requires reindex", null, summary))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
