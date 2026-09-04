package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Supplier;

/**
 * The Referenzvarianten-Selbstprüfung (issue #1041 acceptance criteria, eval/variants/README.md):
 * the reference variant — the one that changes no parameter — must reproduce, field for field, what
 * a second, independent measurement of the unchanged production configuration computes in the same
 * harness run. It is the check that catches the whole class of errors in which a benchmark run
 * measures differently than the regression run.
 *
 * <p><b>Both sides follow the Mehrfachlauf-Regel (issue #1085).</b> As soon as the production
 * configuration itself decomposes, {@link VariantRunner} represents the reference variant by the
 * median of {@link MultiRunAggregator#DECOMPOSITION_RUN_COUNT} runs; comparing that against a
 * single direct run could not be bit-identical for structural reasons, regardless of how stable the
 * chat model is. The direct measurement therefore goes through {@link MehrfachlaufRule} as well, so
 * both sides are the same kind of quantity.
 *
 * <p>Assertions stay hard on purpose: {@code AssertionError} is not a {@code RuntimeException} and
 * is therefore not swallowed by {@link VariantComparisonStep}'s guard — a failure here signals a
 * bug in the variant mechanism itself, not a broken input.
 */
final class ReferenceVariantSelfCheck {

  private ReferenceVariantSelfCheck() {}

  /**
   * @param referenceOutcome the reference variant's outcome as produced by the variant mechanism.
   * @param decompositionEnabled the production configuration's effective decomposition setting —
   *     the same trigger the variant side used, so both sides run the same number of times.
   * @param directMeasure one direct measurement through the harness's own production-wired {@code
   *     QueryService} bean; invoked once or {@link MultiRunAggregator#DECOMPOSITION_RUN_COUNT}
   *     times.
   * @return the direct measurement, for the caller to log or report.
   */
  static MehrfachlaufRule.Measurement assertMatchesDirectMeasurement(
      VariantOutcome referenceOutcome,
      boolean decompositionEnabled,
      Supplier<PipelineEvaluationReport> directMeasure) {
    MehrfachlaufRule.Measurement direct =
        MehrfachlaufRule.measure(decompositionEnabled, directMeasure);
    PipelineEvaluationReport referenceReport = referenceOutcome.report();
    String instabilityHint = instabilityHint(referenceOutcome.multiRun(), direct.summary());

    assertThat(referenceReport.overall())
        .as(
            "reference variant must be bit-identical to a direct pipeline measurement through the "
                + "production-wired QueryService bean (Referenzvarianten-Selbstprüfung, issue "
                + "#1041)%s",
            instabilityHint)
        .isEqualTo(direct.report().overall());
    assertThat(referenceReport.allQueryResults())
        .as(
            "reference variant's per-case results must be bit-identical to the direct measurement%s",
            instabilityHint)
        .isEqualTo(direct.report().allQueryResults());
    // ignoringFields: runStartedAt/runDurationSeconds necessarily differ between two separate
    // measurements taken seconds apart — every other field, including fetchK/maxSubQueries/etc.,
    // must match exactly, so a variant-mechanism bug that silently applied an override the
    // reference variant should not have (a rank-neutral one, invisible in overall()/
    // allQueryResults() because it does not change which chunks were selected) still fails here.
    assertThat(referenceReport.runConfiguration())
        .usingRecursiveComparison()
        .ignoringFields("runStartedAt", "runDurationSeconds")
        .as("reference variant's run configuration must match the direct measurement's")
        .isEqualTo(direct.report().runConfiguration());
    return direct;
  }

  /**
   * Names the decomposition instability of either side when there is any: a difference between two
   * medians of nondeterministic runs is far more likely to come from the chat model than from the
   * variant mechanism, and a failure message that does not say so sends the reader looking in the
   * wrong place.
   */
  private static String instabilityHint(MultiRunSummary reference, MultiRunSummary direct) {
    int deviating =
        (reference == null ? 0 : reference.decompositionDeviatingCaseCount())
            + (direct == null ? 0 : direct.decompositionDeviatingCaseCount());
    if (deviating == 0) {
      return "";
    }
    return ". Beide Seiten sind Median-Läufe der Mehrfachlauf-Regel, und die Zerlegung wich in "
        + deviating
        + " Fall/Fällen zwischen den Läufen ab — bei einem Unterschied ist deshalb zuerst die "
        + "Stabilität des Chat-Modells zu prüfen, nicht die Variantenmechanik";
  }
}
