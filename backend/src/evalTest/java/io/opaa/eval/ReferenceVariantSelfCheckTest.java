package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.query.QueryProperties;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Docker-free unit tests for the Referenzvarianten-Selbstprüfung (issue #1085): with the production
 * configuration decomposing, the reference variant is a median of {@link
 * MultiRunAggregator#DECOMPOSITION_RUN_COUNT} runs, and only a direct measurement that follows the
 * same rule can be bit-identical to it.
 */
class ReferenceVariantSelfCheckTest {

  private static final RerankRunWatch NO_RERANKING =
      new RerankRunWatch() {
        @Override
        public long degradedCallCount() {
          return 0;
        }

        @Override
        public boolean usable() {
          return false;
        }
      };

  private static QueryProperties production(boolean decompositionEnabled) {
    return new QueryProperties(8, 25, 1.0, 0.3, 1.0, decompositionEnabled, 3, 2, true, 50);
  }

  private static PipelineEvaluationReport report(boolean caseAFound) {
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
                null));
    return PipelineRetrievalEvaluator.report(
        PipelineRetrievalEvaluator.evaluateAll(
            goldenCases,
            query ->
                new PipelineRetrievalEvaluator.PipelineInvocationResult(
                    caseAFound ? List.of("a.md") : List.of(), List.of("teilfrage"))),
        VariantComparisonRunnerTest.runConfiguration());
  }

  /**
   * A measurement whose runs differ in a reproducible way: run 0 misses, runs 1 and 2 hit. The
   * median-by-nDCG@8 run is therefore run 1 and never run 0 — which is what makes this fixture able
   * to tell "median on both sides" apart from "median against a single first run".
   */
  private static Supplier<PipelineEvaluationReport> unstableMeasurement() {
    AtomicInteger call = new AtomicInteger();
    return () -> report(call.getAndIncrement() != 0);
  }

  private static VariantOutcome referenceOutcome(
      boolean decompositionEnabled, Supplier<PipelineEvaluationReport> measure) {
    return referenceOutcome(
        PipelineVariant.QueryOverrides.NONE, production(decompositionEnabled), measure);
  }

  private static VariantOutcome referenceOutcome(
      PipelineVariant.QueryOverrides overrides,
      QueryProperties production,
      Supplier<PipelineEvaluationReport> measure) {
    return VariantRunner.run(
        new PipelineVariant("reference", "desc", false, overrides),
        VariantQueryProperties.apply(production, overrides),
        measure,
        NO_RERANKING);
  }

  @Test
  void aDecomposingReferenceVariantIsComparedAgainstAMedianOfTheSameNumberOfDirectRuns() {
    var outcome = referenceOutcome(true, unstableMeasurement());

    var direct =
        ReferenceVariantSelfCheck.assertMatchesDirectMeasurement(
            outcome, production(true), unstableMeasurement());

    assertThat(direct.multiRun()).isTrue();
    assertThat(direct.summary().runCount()).isEqualTo(MultiRunAggregator.DECOMPOSITION_RUN_COUNT);
    assertThat(direct.summary().medianRunIndex()).isEqualTo(1);
  }

  /**
   * The regression this check guards: comparing the reference variant's median against a single
   * direct run. The fixture's first run is deliberately the one the median rule does <b>not</b>
   * pick, so a self-check that took just one direct measurement would compare two different runs
   * and fail — which is exactly what happened when the production configuration became
   * decomposition-capable.
   */
  @Test
  void aMedianReferenceVariantDoesNotMatchASingleDirectRun() {
    var outcome = referenceOutcome(true, unstableMeasurement());
    Supplier<PipelineEvaluationReport> single = unstableMeasurement();

    assertThatThrownBy(
            () ->
                ReferenceVariantSelfCheck.assertMatchesDirectMeasurement(
                    outcome, production(false), single::get))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("Referenzvarianten-Selbstprüfung");
  }

  /** Without decomposition both sides measure exactly once, unchanged from before issue #1085. */
  @Test
  void aNonDecomposingReferenceVariantIsStillComparedAgainstASingleDirectRun() {
    AtomicInteger directCalls = new AtomicInteger();
    var outcome = referenceOutcome(false, () -> report(true));

    var direct =
        ReferenceVariantSelfCheck.assertMatchesDirectMeasurement(
            outcome,
            production(false),
            () -> {
              directCalls.incrementAndGet();
              return report(true);
            });

    assertThat(directCalls.get()).isEqualTo(1);
    assertThat(direct.multiRun()).isFalse();
  }

  /** A stable decomposition passes the check with both sides on three runs. */
  @Test
  void aStableDecompositionPassesTheCheck() {
    var outcome = referenceOutcome(true, () -> report(true));

    assertThatCode(
            () ->
                ReferenceVariantSelfCheck.assertMatchesDirectMeasurement(
                    outcome, production(true), () -> report(true)))
        .doesNotThrowAnyException();
  }

  /**
   * The run count follows the reference variant's <b>effective</b> configuration, not the
   * production value alone: a reference variant that switches decomposition on through an override
   * runs three times on the variant side, so the direct measurement must too.
   */
  @Test
  void aReferenceVariantThatEnablesDecompositionByOverrideAlsoGetsThreeDirectRuns() {
    var overrides =
        new PipelineVariant.QueryOverrides(null, null, null, true, null, null, null, null);
    AtomicInteger directCalls = new AtomicInteger();
    var outcome = referenceOutcome(overrides, production(false), () -> report(true));

    var direct =
        ReferenceVariantSelfCheck.assertMatchesDirectMeasurement(
            outcome,
            production(false),
            () -> {
              directCalls.incrementAndGet();
              return report(true);
            });

    assertThat(directCalls.get()).isEqualTo(MultiRunAggregator.DECOMPOSITION_RUN_COUNT);
    assertThat(direct.multiRun()).isTrue();
  }
}
