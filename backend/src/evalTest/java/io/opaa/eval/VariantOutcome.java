package io.opaa.eval;

/**
 * One variant's outcome within a comparison run (issue #1041): either it ran and produced a {@link
 * PipelineEvaluationReport}, or it was skipped because {@link VariantPrerequisites} found an unmet
 * prerequisite — never both, never neither.
 *
 * @param report for a single-run variant, that run's report; for a variant with an LLM component
 *     (issue #1044, docs/features/retrieval-benchmark.md §3), the <b>median run</b> among the
 *     repeated runs {@link #multiRun} summarizes — see {@link MultiRunSummary}'s Javadoc for why
 *     the median run, not an average, is what every other field of this outcome (worst queries,
 *     per-case deltas, run configuration) is drawn from. {@code null} exactly when {@link
 *     #executed} is {@code false}.
 * @param multiRun {@code null} for a skipped variant and for a variant without an LLM component (it
 *     ran exactly once, nothing to aggregate); the aggregated min/median/max spread and the
 *     decomposition-deviation count otherwise.
 */
public record VariantOutcome(
    PipelineVariant variant,
    boolean executed,
    String skipReason,
    PipelineEvaluationReport report,
    MultiRunSummary multiRun) {

  public VariantOutcome {
    if (executed == (report == null)) {
      throw new IllegalArgumentException(
          "an executed variant outcome must carry a report and no skip reason, a skipped one the "
              + "reverse — got executed="
              + executed
              + " report="
              + report);
    }
    if (executed && skipReason != null) {
      throw new IllegalArgumentException(
          "an executed variant outcome must not carry a skip reason");
    }
    if (!executed && (skipReason == null || skipReason.isBlank())) {
      throw new IllegalArgumentException(
          "a skipped variant outcome must carry a non-blank skip reason");
    }
    if (!executed && multiRun != null) {
      throw new IllegalArgumentException(
          "a skipped variant outcome must not carry a multi-run summary — it never ran at all");
    }
  }

  public static VariantOutcome skipped(PipelineVariant variant, String reason) {
    return new VariantOutcome(variant, false, reason, null, null);
  }

  public static VariantOutcome executed(PipelineVariant variant, PipelineEvaluationReport report) {
    return new VariantOutcome(variant, true, null, report, null);
  }

  /**
   * A variant with an LLM component that ran {@link MultiRunAggregator#DECOMPOSITION_RUN_COUNT}
   * times — {@code medianReport} must be the run {@code multiRun.medianRunIndex()} actually points
   * at; {@link VariantRunner} is the only caller and guarantees that by construction.
   */
  public static VariantOutcome executedMultiRun(
      PipelineVariant variant, PipelineEvaluationReport medianReport, MultiRunSummary multiRun) {
    return new VariantOutcome(variant, true, null, medianReport, multiRun);
  }
}
