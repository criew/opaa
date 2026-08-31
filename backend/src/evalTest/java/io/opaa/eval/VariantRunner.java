package io.opaa.eval;

import io.opaa.indexing.IndexingProperties;
import io.opaa.query.QueryProperties;
import io.opaa.query.QueryService;
import io.opaa.query.QueryServiceDependencies;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Measures one {@link PipelineVariant} against the golden dataset (issue #1041,
 * docs/features/retrieval-benchmark.md §2). Reuses the production pipeline path exactly as the
 * single-configuration measurement (#1039, {@link PipelineHarnessSupport}) does — a variant is a
 * different {@link QueryProperties}, never a reimplementation of retrieval steps 2 to 6.
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
    PipelineEvaluationReport report =
        PipelineHarnessSupport.measure(
            domain,
            identity,
            queryService,
            effective,
            indexingProperties,
            evalLibraryId,
            goldenCases,
            Instant.now());
    return VariantOutcome.executed(variant, report);
  }
}
