package io.opaa.eval;

/**
 * One variant's outcome within a comparison run (issue #1041): either it ran and produced a {@link
 * PipelineEvaluationReport}, or it was skipped because {@link VariantPrerequisites} found an unmet
 * prerequisite — never both, never neither.
 */
public record VariantOutcome(
    PipelineVariant variant, boolean executed, String skipReason, PipelineEvaluationReport report) {

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
  }

  public static VariantOutcome skipped(PipelineVariant variant, String reason) {
    return new VariantOutcome(variant, false, reason, null);
  }

  public static VariantOutcome executed(PipelineVariant variant, PipelineEvaluationReport report) {
    return new VariantOutcome(variant, true, null, report);
  }
}
