package io.opaa.eval;

import io.opaa.indexing.IndexingProperties;
import io.opaa.query.QueryProperties;
import io.opaa.query.QueryService;
import io.opaa.query.QueryServiceDependencies;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Measures one {@link PipelineVariant} against the golden dataset (issue #1041,
 * docs/features/retrieval-benchmark.md §2). Reuses the production pipeline path exactly as the
 * single-configuration measurement (#1039, {@link PipelineHarnessSupport}) does — a variant is a
 * different {@link QueryProperties}, never a reimplementation of retrieval steps 2 to 6.
 *
 * <p><b>Mehrfachlauf-Regel</b> (issue #1044, docs/features/retrieval-benchmark.md §3, "Was
 * stattdessen gilt", 2.–3.): a variant whose effective {@code queryDecompositionEnabled} is {@code
 * true} has an LLM component and is therefore not deterministic — it runs {@link
 * MultiRunAggregator#DECOMPOSITION_RUN_COUNT} times and its outcome carries a {@link
 * MultiRunSummary}. Every other variant runs exactly once, as before this issue; a second run would
 * report the identical numbers, which is itself the tested invariant (see {@code
 * PipelineHarnessSupport}/{@code RetrievalEvaluationHarnessTest}'s bit-identical-run evidence,
 * ADR-0013 Nachtrag), not a reason to spend the wall-clock time confirming it again on every
 * variant comparison.
 */
public final class VariantRunner {

  private VariantRunner() {}

  public static VariantOutcome run(
      PipelineVariant variant,
      QueryServiceDependencies dependencies,
      QueryProperties productionQueryProperties,
      EvalDomainConfig domain,
      PipelineHarnessSupport.RunIdentity identity,
      IndexingProperties indexingProperties,
      UUID evalLibraryId,
      List<GoldenCase> goldenCases) {
    QueryProperties effective =
        VariantQueryProperties.apply(productionQueryProperties, variant.queryOverrides());

    var unmetReason = VariantPrerequisites.unmetReason(variant, effective);
    if (unmetReason.isPresent()) {
      return VariantOutcome.skipped(variant, unmetReason.get());
    }

    QueryService queryService = dependencies.buildQueryService(effective);
    return run(
        variant,
        effective,
        () ->
            PipelineHarnessSupport.measure(
                domain,
                identity,
                queryService,
                effective,
                indexingProperties,
                evalLibraryId,
                goldenCases,
                Instant.now()));
  }

  /**
   * The Mehrfachlauf-Regel's decision logic itself, split out from the public overload above (issue
   * #1044 review, Befund 1) so it is Docker-free testable: {@code measure} stands in for one {@link
   * PipelineHarnessSupport#measure} call, letting {@code VariantRunnerTest} exercise the run count
   * (one vs. {@link MultiRunAggregator#DECOMPOSITION_RUN_COUNT}), the median-run selection and the
   * resulting {@link VariantOutcome} without a real {@code QueryService} or corpus. The public
   * overload's prerequisite check is deliberately <b>not</b> repeated here: by the time this method
   * is reached, {@code effective} is already known to be measurable.
   */
  static VariantOutcome run(
      PipelineVariant variant,
      QueryProperties effective,
      Supplier<PipelineEvaluationReport> measure) {
    if (!effective.queryDecompositionEnabled()) {
      return VariantOutcome.executed(variant, measure.get());
    }

    List<PipelineEvaluationReport> runs =
        new ArrayList<>(MultiRunAggregator.DECOMPOSITION_RUN_COUNT);
    for (int i = 0; i < MultiRunAggregator.DECOMPOSITION_RUN_COUNT; i++) {
      runs.add(measure.get());
    }
    MultiRunSummary summary = MultiRunAggregator.summarize(runs);
    return VariantOutcome.executedMultiRun(variant, runs.get(summary.medianRunIndex()), summary);
  }
}
