package io.opaa.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The Mehrfachlauf-Regel itself (issue #1044, docs/features/retrieval-benchmark.md §3, "Was
 * stattdessen gilt", 2.–3.), in one place for every caller that measures the pipeline: a
 * configuration with active Teilfragen-Zerlegung has an LLM component and is therefore not
 * deterministic — it is measured {@link MultiRunAggregator#DECOMPOSITION_RUN_COUNT} times and
 * represented by its median run. Every other configuration is measured exactly once.
 *
 * <p>Shared by {@link VariantRunner}, the single-configuration pipeline path ({@link
 * PipelineHarnessSupport#runAndWriteGuarded}) and the Referenzvarianten-Selbstprüfung ({@link
 * ReferenceVariantSelfCheck}) since issue #1085: the self-check compares two independent
 * measurements of the same configuration, and a median of three runs on one side against a single
 * run on the other could not be bit-identical for structural reasons alone.
 */
final class MehrfachlaufRule {

  private MehrfachlaufRule() {}

  /**
   * @param report the measurement this configuration is represented by — the single run, or the
   *     median run of the repeated ones.
   * @param summary the spread across the repeated runs, or {@code null} for a single-run
   *     measurement.
   */
  record Measurement(PipelineEvaluationReport report, MultiRunSummary summary) {

    boolean multiRun() {
      return summary != null;
    }
  }

  static Measurement measure(
      boolean decompositionEnabled, Supplier<PipelineEvaluationReport> measure) {
    if (!decompositionEnabled) {
      return new Measurement(measure.get(), null);
    }
    List<PipelineEvaluationReport> runs =
        new ArrayList<>(MultiRunAggregator.DECOMPOSITION_RUN_COUNT);
    for (int i = 0; i < MultiRunAggregator.DECOMPOSITION_RUN_COUNT; i++) {
      runs.add(measure.get());
    }
    MultiRunSummary summary = MultiRunAggregator.summarize(runs);
    return new Measurement(runs.get(summary.medianRunIndex()), summary);
  }

  /** One line per metric plus the deviation count — the Mehrfachlauf-Bericht of a measurement. */
  static String render(MultiRunSummary summary) {
    return "Mehrfachlauf ("
        + summary.runCount()
        + " Läufe, Median-Lauf #"
        + summary.medianRunIndex()
        + "):\n"
        + renderMetric("Hit Rate@5", summary.hitRateAt5())
        + renderMetric("MRR@8", summary.mrrAt8())
        + renderMetric("nDCG@8", summary.ndcgAt8())
        + renderMetric("Recall@8", summary.recallAt8())
        + "  Fälle mit abweichender Zerlegung: "
        + summary.decompositionDeviatingCaseCount()
        + (summary.decompositionDeviatingCaseIds().isEmpty()
            ? ""
            : " " + summary.decompositionDeviatingCaseIds());
  }

  private static String renderMetric(String name, MultiRunSummary.MetricRange range) {
    return "  %-11s min %.3f / median %.3f / max %.3f%n"
        .formatted(name, range.min(), range.median(), range.max());
  }
}
