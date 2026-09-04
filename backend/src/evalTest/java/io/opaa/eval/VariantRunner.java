package io.opaa.eval;

import io.opaa.indexing.IndexingProperties;
import io.opaa.query.QueryProperties;
import io.opaa.query.QueryService;
import io.opaa.query.QueryServiceDependencies;
import java.time.Instant;
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
    RerankRunWatch rerankWatch = RerankRunWatch.of(dependencies.rerankModelRole());

    var unmetReason =
        VariantPrerequisites.unmetReason(
            variant,
            effective,
            identity.chatModel() != null,
            identity.fullTextIndexComplete(),
            rerankWatch.usable());
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
                Instant.now()),
        rerankWatch);
  }

  /**
   * The Mehrfachlauf-Regel's decision logic itself, split out from the public overload above (issue
   * #1044 review, Befund 1) so it is Docker-free testable: {@code measure} stands in for one {@link
   * PipelineHarnessSupport#measure} call, letting {@code VariantRunnerTest} exercise the run count
   * (one vs. {@link MultiRunAggregator#DECOMPOSITION_RUN_COUNT}), the median-run selection and the
   * resulting {@link VariantOutcome} without a real {@code QueryService} or corpus. The public
   * overload's prerequisite check is deliberately <b>not</b> repeated here: by the time this method
   * is reached, {@code effective} is already known to be measurable.
   *
   * <p><b>{@code rerankWatch} is read before and after the measurement</b>, not only before it (see
   * {@link RerankRunWatch}): a variant whose reranking dropped out part-way through measured a
   * third configuration — neither the reranked one nor the one configured without reranking — and
   * is reported as not measurable instead of as a number.
   */
  static VariantOutcome run(
      PipelineVariant variant,
      QueryProperties effective,
      Supplier<PipelineEvaluationReport> measure,
      RerankRunWatch rerankWatch) {
    boolean reranks = effective.rerankCandidateCount() > 0 && rerankWatch.usable();
    long degradedBefore = rerankWatch.degradedCallCount();

    VariantOutcome outcome = measureAll(variant, effective, measure);
    if (!reranks) {
      return outcome;
    }
    long degradedDuringRun = rerankWatch.degradedCallCount() - degradedBefore;
    if (degradedDuringRun == 0 && rerankWatch.usable()) {
      return outcome;
    }
    return VariantOutcome.notMeasurable(
        variant,
        "Diese Variante hat mit nutzbarer Rerank-Modellrolle begonnen, aber während des Laufs "
            + "hat die Rolle nicht durchgehend geliefert ("
            + degradedDuringRun
            + " Aufruf(e) ohne verwertbare Rangfolge, Rolle am Ende "
            + (rerankWatch.usable() ? "wieder nutzbar" : "nicht nutzbar")
            + "). Die betroffenen Fragen sind auf die fusionierte Reihenfolge des verbreiterten "
            + "Fensters zurückgefallen — weder das Ergebnis mit Reranking noch das der "
            + "Konfiguration ohne Reranking. Die Zahlen dieses Laufs sind deshalb nicht "
            + "verwertbar; Endpunkt prüfen und die Variante wiederholen.");
  }

  private static VariantOutcome measureAll(
      PipelineVariant variant,
      QueryProperties effective,
      Supplier<PipelineEvaluationReport> measure) {
    MehrfachlaufRule.Measurement measurement =
        MehrfachlaufRule.measure(effective.queryDecompositionEnabled(), measure);
    return measurement.multiRun()
        ? VariantOutcome.executedMultiRun(variant, measurement.report(), measurement.summary())
        : VariantOutcome.executed(variant, measurement.report());
  }
}
