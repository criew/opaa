package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.query.QueryProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Docker-free unit tests for {@link VariantRunner}'s Mehrfachlauf-Regel decision (issue #1044
 * review, Befund 1): until a chat model is wired into the harness (issue #1085), {@code
 * VariantPrerequisites} skips every decomposition-enabled variant before it ever reaches this
 * logic, so it would otherwise go completely untested. These tests exercise the package-private
 * {@link VariantRunner#run(PipelineVariant, QueryProperties, java.util.function.Supplier)} overload
 * directly, standing in for the real {@code QueryService}/corpus with a counting supplier.
 */
class VariantRunnerTest {

  private static PipelineVariant variant(String name, PipelineVariant.QueryOverrides overrides) {
    return new PipelineVariant(name, "desc", false, overrides);
  }

  private static QueryProperties productionProperties(boolean queryDecompositionEnabled) {
    return new QueryProperties(8, 25, 1.0, 0.3, 1.0, queryDecompositionEnabled, 3, 2, true);
  }

  /**
   * A report whose overall nDCG@8 is controllable, so median selection is deterministic to test.
   */
  private static PipelineEvaluationReport reportWithNdcg(double hitRateForCaseA) {
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
    Map<String, List<String>> rankedFileNames =
        Map.of("frage a", hitRateForCaseA > 0 ? List.of("a.md") : List.of());
    return PipelineRetrievalEvaluator.report(
        PipelineRetrievalEvaluator.evaluateAll(
            goldenCases,
            query ->
                new PipelineRetrievalEvaluator.PipelineInvocationResult(
                    rankedFileNames.get(query), List.of("teilfrage"))),
        VariantComparisonRunnerTest.runConfiguration());
  }

  @Test
  void runsOnceWhenEffectiveDecompositionIsDisabled() {
    AtomicInteger calls = new AtomicInteger();
    QueryProperties effective = productionProperties(false);

    var outcome =
        VariantRunner.run(
            variant("v", PipelineVariant.QueryOverrides.NONE),
            effective,
            () -> {
              calls.incrementAndGet();
              return reportWithNdcg(1);
            });

    assertThat(calls.get()).isEqualTo(1);
    assertThat(outcome.executed()).isTrue();
    assertThat(outcome.multiRun()).isNull();
  }

  @Test
  void runsThreeTimesWhenDecompositionIsEnabledInTheBaseProductionConfiguration() {
    AtomicInteger calls = new AtomicInteger();
    QueryProperties effective = productionProperties(true);

    var outcome =
        VariantRunner.run(
            variant("v", PipelineVariant.QueryOverrides.NONE),
            effective,
            () -> {
              calls.incrementAndGet();
              return reportWithNdcg(1);
            });

    assertThat(calls.get()).isEqualTo(MultiRunAggregator.DECOMPOSITION_RUN_COUNT);
    assertThat(outcome.multiRun()).isNotNull();
  }

  /**
   * The trigger reads the variant's <b>effective</b> configuration, not its raw overrides: a
   * variant that only turns decomposition on through {@link VariantQueryProperties#apply} must run
   * three times exactly like one where the production configuration itself already has it enabled.
   */
  @Test
  void runsThreeTimesWhenDecompositionIsEnabledOnlyThroughAnOverride() {
    AtomicInteger calls = new AtomicInteger();
    var overrides = new PipelineVariant.QueryOverrides(null, null, null, true, null, null, null);
    QueryProperties effective =
        VariantQueryProperties.apply(productionProperties(false), overrides);
    assertThat(effective.queryDecompositionEnabled()).isTrue();

    var outcome =
        VariantRunner.run(
            variant("decomposition-on", overrides),
            effective,
            () -> {
              calls.incrementAndGet();
              return reportWithNdcg(1);
            });

    assertThat(calls.get()).isEqualTo(MultiRunAggregator.DECOMPOSITION_RUN_COUNT);
    assertThat(outcome.multiRun()).isNotNull();
  }

  @Test
  void theReportedOutcomeIsExactlyTheMedianRun() {
    QueryProperties effective = productionProperties(true);
    // Distinct nDCG@8 per run via distinct hit/no-hit outcomes for the one case: run 1 misses
    // (nDCG@8 = 0.0), runs 0 and 2 hit (nDCG@8 = 1.0). MultiRunAggregator's stable sort keeps tied
    // values in original run order, so the median-by-nDCG@8 selection is run 0, not run 2 — this
    // assertion is exact, not merely "one of the tied runs".
    List<PipelineEvaluationReport> runsInOrder = new ArrayList<>();
    var outcome =
        VariantRunner.run(
            variant("v", PipelineVariant.QueryOverrides.NONE),
            effective,
            () -> {
              PipelineEvaluationReport report = reportWithNdcg(runsInOrder.size() == 1 ? 0 : 1);
              runsInOrder.add(report);
              return report;
            });

    assertThat(runsInOrder).hasSize(3);
    assertThat(outcome.multiRun().medianRunIndex()).isEqualTo(0);
    assertThat(outcome.report()).isSameAs(runsInOrder.get(0));
  }

  /**
   * Issue #1044 review, Befund 1: a fixture where only the third run's decomposition differs for
   * one case — the deviation count and id list must reflect exactly that, not "any pair differs".
   */
  @Test
  void decompositionDeviatingOnlyInTheThirdRunIsCountedOnce() {
    QueryProperties effective = productionProperties(true);
    List<List<String>> subQueriesPerRun =
        List.of(List.of("teilfrage 1"), List.of("teilfrage 1"), List.of("andere teilfrage"));
    AtomicInteger calls = new AtomicInteger();

    var outcome =
        VariantRunner.run(
            variant("v", PipelineVariant.QueryOverrides.NONE),
            effective,
            () -> {
              int run = calls.getAndIncrement();
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
                              List.of("a.md"), subQueriesPerRun.get(run))),
                  VariantComparisonRunnerTest.runConfiguration());
            });

    assertThat(outcome.multiRun().decompositionDeviatingCaseCount()).isEqualTo(1);
    assertThat(outcome.multiRun().decompositionDeviatingCaseIds()).containsExactly("a");
  }
}
