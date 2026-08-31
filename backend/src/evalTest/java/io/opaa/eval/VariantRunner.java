package io.opaa.eval;

import io.opaa.indexing.IndexingProperties;
import io.opaa.query.QueryProperties;
import io.opaa.query.QueryService;
import io.opaa.query.QueryServiceDependencies;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
    if (!effective.queryDecompositionEnabled()) {
      PipelineEvaluationReport report =
          measureOnce(
              domain,
              identity,
              queryService,
              effective,
              indexingProperties,
              evalLibraryId,
              goldenCases);
      return VariantOutcome.executed(variant, report);
    }

    List<PipelineEvaluationReport> runs =
        new ArrayList<>(MultiRunAggregator.DECOMPOSITION_RUN_COUNT);
    for (int i = 0; i < MultiRunAggregator.DECOMPOSITION_RUN_COUNT; i++) {
      runs.add(
          measureOnce(
              domain,
              identity,
              queryService,
              effective,
              indexingProperties,
              evalLibraryId,
              goldenCases));
    }
    MultiRunSummary summary = MultiRunAggregator.summarize(runs);
    return VariantOutcome.executedMultiRun(variant, runs.get(summary.medianRunIndex()), summary);
  }

  private static PipelineEvaluationReport measureOnce(
      EvalDomainConfig domain,
      PipelineHarnessSupport.RunIdentity identity,
      QueryService queryService,
      QueryProperties effective,
      IndexingProperties indexingProperties,
      UUID evalLibraryId,
      List<GoldenCase> goldenCases) {
    return PipelineHarnessSupport.measure(
        domain,
        identity,
        queryService,
        effective,
        indexingProperties,
        evalLibraryId,
        goldenCases,
        Instant.now());
  }
}
