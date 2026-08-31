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

  private static PipelineEvaluationReport report() {
    var goldenCase =
        new GoldenCase("a", "test", "frage", List.of("a.md"), "cat", "easy", "de", "t", null);
    return PipelineRetrievalEvaluator.report(
        PipelineRetrievalEvaluator.evaluateAll(
            List.of(goldenCase), Map.of("frage", List.of("a.md"))::get),
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
