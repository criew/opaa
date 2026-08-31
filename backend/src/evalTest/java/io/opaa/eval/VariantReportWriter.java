package io.opaa.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    // Column width sized to the actual variant names in this report (issue #1041 review, Befund
    // 10) rather than a fixed guess: a name longer than a fixed width would not misalign the
    // columns of every *other* row the way a too-narrow fixed width otherwise would.
    int nameWidth =
        report.outcomes().stream().mapToInt(o -> o.variant().name().length()).max().orElse(0) + 2;

    sb.append("Varianten:\n");
    for (VariantOutcome outcome : report.outcomes()) {
      String paddedName = pad(outcome.variant().name(), nameWidth);
      if (outcome.executed()) {
        var a = outcome.report().overall();
        sb.append(
            format(
                "  %s (%s)\n      ausgeführt  HitRate@5=%.3f MRR@8=%.3f nDCG@8=%.3f Recall@8=%.3f\n",
                paddedName,
                describeOverrides(outcome.variant().queryOverrides()),
                a.hitRateAt5(),
                a.mrrAt8(),
                a.ndcgAt8(),
                a.recallAt8()));
        if (outcome.multiRun() != null) {
          sb.append(renderMultiRun(outcome.multiRun()));
        }
      } else {
        sb.append(
            format(
                "  %s (%s)\n      nicht ausgeführt — %s\n",
                paddedName,
                describeOverrides(outcome.variant().queryOverrides()),
                outcome.skipReason()));
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

  /**
   * The Mehrfachlauf-Regel's own reporting requirement (issue #1044,
   * docs/features/retrieval-benchmark.md §3): minimum, median and maximum per metric, plus the
   * deviation count that is "die eigentliche Kennzahl der Instabilität" — never a significance
   * claim, only the three numbers and a count.
   */
  private static String renderMultiRun(MultiRunSummary summary) {
    StringBuilder sb = new StringBuilder();
    // The "median=" figure below is the median VALUE of that one metric across the runs, computed
    // independently per metric — it can therefore come from a different run than the "Median-Lauf"
    // named here, which is chosen once, by nDCG@8 (see MultiRunAggregator#medianIndexByNdcg), and
    // is what the delta against the reference variant above this block actually compares. Reading
    // every "median=" figure as if it came from that same single run is the apparent contradiction
    // this note heads off.
    sb.append(
        format(
            "      %d Läufe (Median-Lauf, gewählt nach nDCG@8, gegen Referenz verglichen):\n",
            summary.runCount()));
    sb.append(format("        HitRate@5: %s\n", renderRange(summary.hitRateAt5())));
    sb.append(format("        MRR@8:     %s\n", renderRange(summary.mrrAt8())));
    sb.append(format("        nDCG@8:    %s\n", renderRange(summary.ndcgAt8())));
    sb.append(format("        Recall@8:  %s\n", renderRange(summary.recallAt8())));
    sb.append(
        format(
            "        Zerlegung wich bei %d Fällen zwischen den Läufen ab%s\n",
            summary.decompositionDeviatingCaseCount(),
            summary.decompositionDeviatingCaseIds().isEmpty()
                ? ""
                : ": " + String.join(", ", summary.decompositionDeviatingCaseIds())));
    return sb.toString();
  }

  private static String renderRange(MultiRunSummary.MetricRange range) {
    return format("min=%.3f median=%.3f max=%.3f", range.min(), range.median(), range.max());
  }

  private static String pad(String value, int width) {
    return format("%-" + width + "s", value);
  }

  /**
   * Lists a variant's effectively changed parameters, or states there are none — a Δ0.000 line for
   * a variant with no listed parameter is then indistinguishable from one whose override happened
   * to have no measurable effect (issue #1041 review, Befund 5), which the reader must be able to
   * tell apart without opening the JSON report.
   */
  private static String describeOverrides(PipelineVariant.QueryOverrides overrides) {
    List<String> parts = new ArrayList<>();
    if (overrides.fetchK() != null) {
      parts.add("fetchK=" + overrides.fetchK());
    }
    if (overrides.mmrLambda() != null) {
      parts.add("mmrLambda=" + overrides.mmrLambda());
    }
    if (overrides.similarityThreshold() != null) {
      parts.add("similarityThreshold=" + overrides.similarityThreshold());
    }
    if (overrides.queryDecompositionEnabled() != null) {
      parts.add("queryDecompositionEnabled=" + overrides.queryDecompositionEnabled());
    }
    if (overrides.maxSubQueries() != null) {
      parts.add("maxSubQueries=" + overrides.maxSubQueries());
    }
    if (overrides.maxChunksPerDocument() != null) {
      parts.add("maxChunksPerDocument=" + overrides.maxChunksPerDocument());
    }
    return parts.isEmpty() ? "keine Änderung" : String.join(", ", parts);
  }

  // Explicit Locale.ROOT per call, deliberately not a JVM-wide Locale.setDefault(Locale.ROOT) —
  // same reasoning as ReportWriter#format / PipelineReportWriter#format.
  private static String format(String pattern, Object... args) {
    return String.format(Locale.ROOT, pattern, args);
  }
}
