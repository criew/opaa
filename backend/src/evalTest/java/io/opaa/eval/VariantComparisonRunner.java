package io.opaa.eval;

import io.opaa.eval.PipelineEvaluationReport.PipelineQueryResult;
import io.opaa.indexing.IndexingProperties;
import io.opaa.query.QueryProperties;
import io.opaa.query.QueryServiceDependencies;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs every variant of a {@link VariantComparison} and assembles the {@link VariantReport} (issue
 * #1041, docs/features/retrieval-benchmark.md §2).
 */
public final class VariantComparisonRunner {

  private VariantComparisonRunner() {}

  public static VariantReport run(
      VariantComparison comparison,
      QueryServiceDependencies dependencies,
      QueryProperties productionQueryProperties,
      EvalDomainConfig domain,
      PipelineHarnessSupport.RunIdentity identity,
      IndexingProperties indexingProperties,
      UUID evalLibraryId,
      List<GoldenCase> goldenCases) {
    // Defense in depth: the harness already calls this before indexing (issue #1041 review, Befund
    // 3), but this method has its own callers (VariantComparisonRunnerTest exercises #delta
    // directly, not this method, so this is not redundant with that) and must not silently produce
    // a reference-less report if invoked without that earlier check.
    comparison.requireExecutableReference(productionQueryProperties);

    List<VariantOutcome> outcomes = new ArrayList<>(comparison.variants().size());
    for (PipelineVariant variant : comparison.variants()) {
      outcomes.add(
          VariantRunner.run(
              variant,
              dependencies,
              productionQueryProperties,
              domain,
              identity,
              indexingProperties,
              evalLibraryId,
              goldenCases));
    }

    VariantOutcome referenceOutcome =
        outcomes.stream()
            .filter(o -> o.variant().name().equals(comparison.referenceVariant()))
            .findFirst()
            .orElseThrow();
    if (!referenceOutcome.executed()) {
      throw new IllegalStateException(
          "Referenzvariante '"
              + comparison.referenceVariant()
              + "' der Variantenvergleich '"
              + comparison.name()
              + "' konnte nicht ausgeführt werden ("
              + referenceOutcome.skipReason()
              + ") — jedes Delta dieses Berichts ist gegen sie gepaart, ein Bericht ohne sie ist "
              + "sinnlos. Voraussetzungen der Referenzvariante prüfen (docs/features/"
              + "retrieval-benchmark.md, Abschnitt 2).");
    }

    List<VariantReport.VariantComparisonAgainstReference> comparisons = new ArrayList<>();
    for (VariantOutcome outcome : outcomes) {
      if (!outcome.executed() || outcome.variant().name().equals(comparison.referenceVariant())) {
        continue;
      }
      comparisons.add(delta(outcome, referenceOutcome));
    }

    return new VariantReport(
        comparison.name(),
        comparison.description(),
        comparison.domain(),
        comparison.referenceVariant(),
        List.copyOf(outcomes),
        List.copyOf(comparisons));
  }

  // Package-private, not private: VariantComparisonRunnerTest exercises the delta computation
  // directly with synthetic reports, since VariantComparisonRunner#run itself needs a real
  // QueryService (Spring/Docker) via VariantRunner and is therefore only exercised end to end by
  // the Docker-requiring RetrievalEvaluationHarnessTest.
  static VariantReport.VariantComparisonAgainstReference delta(
      VariantOutcome variantOutcome, VariantOutcome referenceOutcome) {
    PipelineEvaluationReport variantReport = variantOutcome.report();
    PipelineEvaluationReport referenceReport = referenceOutcome.report();
    PipelineMetricsAggregate variantAggregate = variantReport.overall();
    PipelineMetricsAggregate referenceAggregate = referenceReport.overall();

    VariantReport.AggregateDelta aggregateDelta =
        new VariantReport.AggregateDelta(
            variantAggregate.hitRateAt5() - referenceAggregate.hitRateAt5(),
            variantAggregate.mrrAt8() - referenceAggregate.mrrAt8(),
            variantAggregate.ndcgAt8() - referenceAggregate.ndcgAt8(),
            variantAggregate.recallAt8() - referenceAggregate.recallAt8());

    Map<String, PipelineQueryResult> referenceByCaseId = new HashMap<>();
    for (PipelineQueryResult result : referenceReport.allQueryResults()) {
      referenceByCaseId.put(result.id(), result);
    }

    List<VariantReport.CaseDelta> caseDeltas =
        new ArrayList<>(variantReport.allQueryResults().size());
    for (PipelineQueryResult caseResult : variantReport.allQueryResults()) {
      PipelineQueryResult referenceCase = referenceByCaseId.get(caseResult.id());
      if (referenceCase == null) {
        // Both variants ran the very same golden dataset (VariantRunner passes goldenCases through
        // unchanged) — a missing id here would mean the two reports disagree about which cases
        // exist, breaking the "gepaarte Messung" the whole mechanism is built to guarantee.
        throw new IllegalStateException(
            "Variante '"
                + variantOutcome.variant().name()
                + "' hat den Fall '"
                + caseResult.id()
                + "' ausgewertet, den die Referenzvariante nicht kennt — beide müssen dasselbe "
                + "Golden Dataset sehen (docs/features/retrieval-benchmark.md, Abschnitt 2).");
      }
      caseDeltas.add(
          new VariantReport.CaseDelta(
              caseResult.id(),
              caseResult.query(),
              caseResult.category(),
              caseResult.hitRateAt5() - referenceCase.hitRateAt5(),
              caseResult.reciprocalRankAt8() - referenceCase.reciprocalRankAt8(),
              caseResult.ndcgAt8() - referenceCase.ndcgAt8(),
              caseResult.recallAt8() - referenceCase.recallAt8()));
    }
    // Worst-first by nDCG@8 delta: the specification calls "which five questions got worse" the
    // interesting information, not the aggregate mean.
    caseDeltas.sort(Comparator.comparingDouble(VariantReport.CaseDelta::ndcgAt8Delta));

    return new VariantReport.VariantComparisonAgainstReference(
        variantOutcome.variant().name(), aggregateDelta, caseDeltas);
  }
}
