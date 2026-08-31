package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VariantReportWriterTest {

  @TempDir Path tempDir;

  private static PipelineVariant variant(String name) {
    return new PipelineVariant(name, "desc", false, PipelineVariant.QueryOverrides.NONE);
  }

  private static PipelineVariant variantWithOverrides(
      String name, PipelineVariant.QueryOverrides overrides) {
    return new PipelineVariant(name, "desc", false, overrides);
  }

  private static PipelineEvaluationReport report() {
    var goldenCase =
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
            null);
    return PipelineRetrievalEvaluator.report(
        PipelineRetrievalEvaluator.evaluateAll(
            List.of(goldenCase),
            VariantComparisonRunnerTest.toPipeline(Map.of("frage", List.of("a.md")))),
        VariantComparisonRunnerTest.runConfiguration());
  }

  @Test
  void writesAndRendersASkippedVariantWithoutAReport() {
    var reference = VariantOutcome.executed(variant("reference"), report());
    var skipped = VariantOutcome.skipped(variant("needs-reindex"), "requires reindex");
    var comparisonAgainstReference = VariantComparisonRunner.delta(reference, reference);
    var report =
        new VariantReport(
            "c",
            "desc",
            "comic-characters",
            "reference",
            List.of(reference, skipped),
            List.of(comparisonAgainstReference));

    String summary = VariantReportWriter.renderSummary(report);

    assertThat(summary)
        .contains("reference")
        .contains("needs-reindex")
        .contains("requires reindex");
  }

  /**
   * Issue #1041 review (second round): the effectively-changed-parameters line must actually name
   * the override on a changed variant and say so explicitly on an unchanged one — otherwise a
   * variant that changed nothing and a variant whose override happened to have no measurable effect
   * are indistinguishable in the summary (the whole point of {@code describeOverrides}).
   */
  @Test
  void rendersTheEffectivelyChangedParametersPerVariant() {
    var reference = VariantOutcome.executed(variant("reference"), report());
    var mmrOverride = new PipelineVariant.QueryOverrides(null, 0.7, null, null, null, null);
    var changed = VariantOutcome.executed(variantWithOverrides("mmr-0.7", mmrOverride), report());
    var comparisonAgainstReference = VariantComparisonRunner.delta(changed, reference);
    var report =
        new VariantReport(
            "c",
            "desc",
            "comic-characters",
            "reference",
            List.of(reference, changed),
            List.of(comparisonAgainstReference));

    String summary = VariantReportWriter.renderSummary(report);

    assertThat(summary).contains("keine Änderung");
    assertThat(summary).contains("mmrLambda=0.7");
  }

  /**
   * Issue #1044 review, Befund 1(c): a multi-run variant's summary must show the min/median/max
   * lines and the deviation line, and the deviation count must reflect a fixture where only the
   * third of three runs actually differs — not "any pair differs" or an off-by-one over the runs.
   */
  @Test
  void rendersMultiRunMinMedianMaxAndTheDeviationLine() {
    var reference = VariantOutcome.executed(variant("reference"), report());
    var goldenCase =
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
            null);
    List<List<String>> subQueriesPerRun =
        List.of(List.of("teilfrage 1"), List.of("teilfrage 1"), List.of("andere teilfrage"));
    List<PipelineEvaluationReport> runs =
        subQueriesPerRun.stream()
            .map(
                subQueries ->
                    PipelineRetrievalEvaluator.report(
                        PipelineRetrievalEvaluator.evaluateAll(
                            List.of(goldenCase),
                            query ->
                                new PipelineRetrievalEvaluator.PipelineInvocationResult(
                                    List.of("a.md"), subQueries)),
                        VariantComparisonRunnerTest.runConfiguration()))
            .toList();
    MultiRunSummary summary = MultiRunAggregator.summarize(runs);
    var decompositionOn = new PipelineVariant.QueryOverrides(null, null, null, true, null, null);
    var multiRunOutcome =
        VariantOutcome.executedMultiRun(
            variantWithOverrides("decomposition-on", decompositionOn),
            runs.get(summary.medianRunIndex()),
            summary);
    var comparisonAgainstReference = VariantComparisonRunner.delta(multiRunOutcome, reference);
    var report =
        new VariantReport(
            "c",
            "desc",
            "comic-characters",
            "reference",
            List.of(reference, multiRunOutcome),
            List.of(comparisonAgainstReference));

    String rendered = VariantReportWriter.renderSummary(report);

    assertThat(rendered).contains("3 Läufe");
    assertThat(rendered).contains("HitRate@5: min=").contains("median=").contains("max=");
    assertThat(rendered).contains("Zerlegung wich bei 1 Fällen zwischen den Läufen ab: a");
  }

  @Test
  void writesValidJsonToTheTargetFile() throws Exception {
    var reference = VariantOutcome.executed(variant("reference"), report());
    var report =
        new VariantReport(
            "c", "desc", "comic-characters", "reference", List.of(reference), List.of());
    Path target = tempDir.resolve("variant-report.json");

    VariantReportWriter.writeJson(report, target);

    assertThat(Files.readString(target))
        .contains("\"comparisonName\"")
        .contains("comic-characters");
  }
}
