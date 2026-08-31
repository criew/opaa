package io.opaa.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the {@link VariantReport} as JSON and as a human-readable summary (issue #1041). Never
 * committed — see eval/README.md, "Der Bericht ist ein Artefakt, keine Baseline".
 */
public final class VariantReportWriter {

  private VariantReportWriter() {}

  public static void writeJson(VariantReport report, Path target) throws IOException {
    Files.createDirectories(target.getParent());
    JsonMapper mapper = JsonMapper.builder().build();
    Files.writeString(
        target,
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
        StandardCharsets.UTF_8);
  }

  public static String renderSummary(VariantReport report) {
    StringBuilder sb = new StringBuilder();
    sb.append(
        format(
            "\n=== Variantenvergleich: %s (%s) ===\n\n", report.comparisonName(), report.domain()));
    sb.append(format("%s\n", report.comparisonDescription()));
    sb.append(format("Referenzvariante: %s\n\n", report.referenceVariant()));

    sb.append("Varianten:\n");
    for (VariantOutcome outcome : report.outcomes()) {
      if (outcome.executed()) {
        var a = outcome.report().overall();
        sb.append(
            format(
                "  %-32s ausgeführt  HitRate@5=%.3f MRR@8=%.3f nDCG@8=%.3f Recall@8=%.3f\n",
                outcome.variant().name(), a.hitRateAt5(), a.mrrAt8(), a.ndcgAt8(), a.recallAt8()));
      } else {
        sb.append(
            format(
                "  %-32s nicht ausgeführt — %s\n", outcome.variant().name(), outcome.skipReason()));
      }
    }
    sb.append('\n');

    for (var comparison : report.comparisons()) {
      var d = comparison.aggregateDelta();
      sb.append(
          format(
              "%s vs. %s: ΔHitRate@5=%+.3f ΔMRR@8=%+.3f ΔnDCG@8=%+.3f ΔRecall@8=%+.3f\n",
              comparison.variantName(),
              report.referenceVariant(),
              d.hitRateAt5Delta(),
              d.mrrAt8Delta(),
              d.ndcgAt8Delta(),
              d.recallAt8Delta()));
      long improved = comparison.caseDeltas().stream().filter(c -> c.ndcgAt8Delta() > 0).count();
      long regressed = comparison.caseDeltas().stream().filter(c -> c.ndcgAt8Delta() < 0).count();
      sb.append(
          format(
              "  %d von %d Fällen verbessert, %d verschlechtert (nach nDCG@8)\n",
              improved, comparison.caseDeltas().size(), regressed));
      comparison.caseDeltas().stream()
          .filter(c -> c.ndcgAt8Delta() < 0)
          .limit(5)
          .forEach(
              c ->
                  sb.append(
                      format(
                          "    [%s] ΔnDCG@8=%+.3f — \"%s\"\n",
                          c.category(), c.ndcgAt8Delta(), c.query())));
    }
    return sb.toString();
  }

  // Explicit Locale.ROOT per call, deliberately not a JVM-wide Locale.setDefault(Locale.ROOT) —
  // same reasoning as ReportWriter#format / PipelineReportWriter#format.
  private static String format(String pattern, Object... args) {
    return String.format(Locale.ROOT, pattern, args);
  }
}
