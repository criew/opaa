package io.opaa.eval;

import java.util.List;

/**
 * The variant-comparison artifact (issue #1041, docs/features/retrieval-benchmark.md §2): every
 * variant's outcome — executed with its own {@link PipelineEvaluationReport}, or skipped with a
 * reason ({@link VariantOutcome}) — plus, for every executed non-reference variant, its delta
 * against the comparison's reference variant, both aggregated and per golden case ("gepaarte
 * Messung", same specification section). Never committed (see eval/README.md, "Der Bericht ist ein
 * Artefakt, keine Baseline"): a fresh file under {@code build/eval-reports/} on every run.
 */
public record VariantReport(
    String comparisonName,
    String comparisonDescription,
    String domain,
    String referenceVariant,
    List<VariantOutcome> outcomes,
    List<VariantComparisonAgainstReference> comparisons) {

  /** One executed, non-reference variant's delta against the reference variant. */
  public record VariantComparisonAgainstReference(
      String variantName, AggregateDelta aggregateDelta, List<CaseDelta> caseDeltas) {}

  /**
   * Aggregated delta at the pipeline path's fixed window (Hit Rate@5, MRR@8, nDCG@8, Recall@8) —
   * positive means the variant scored higher than the reference variant.
   */
  public record AggregateDelta(
      double hitRateAt5Delta, double mrrAt8Delta, double ndcgAt8Delta, double recallAt8Delta) {}

  /**
   * One golden case's delta between a variant and the reference variant — the "welche fünf Fragen
   * haben sich verschlechtert" information the specification calls the actually interesting one,
   * next to the aggregate.
   */
  public record CaseDelta(
      String caseId,
      String query,
      String category,
      double hitRateAt5Delta,
      double reciprocalRankAt8Delta,
      double ndcgAt8Delta,
      double recallAt8Delta) {}
}
