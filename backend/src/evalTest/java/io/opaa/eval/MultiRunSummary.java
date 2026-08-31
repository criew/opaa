package io.opaa.eval;

import java.util.List;

/**
 * Statistics over the repeated runs of one LLM-behaftete {@link PipelineVariant} (issue #1044,
 * docs/features/retrieval-benchmark.md §3, "Was stattdessen gilt", 2.). Attached to a {@link
 * VariantOutcome} only for a variant whose effective {@code queryDecompositionEnabled} is {@code
 * true} — a variant without an LLM component runs once and never carries this.
 *
 * <p>{@link MultiRunAggregator#summarize} is the only producer; see its Javadoc for the "median
 * run" choice this record's {@code report()}-facing {@link VariantOutcome} relies on.
 *
 * @param runCount how many times the variant ran (fixed at {@link
 *     MultiRunAggregator#DECOMPOSITION_RUN_COUNT} by the specification, carried here rather than
 *     hard-coded again at every reader).
 * @param hitRateAt5 minimum, median and maximum Hit Rate@5 across the runs.
 * @param mrrAt8 minimum, median and maximum MRR@8 across the runs.
 * @param ndcgAt8 minimum, median and maximum nDCG@8 across the runs.
 * @param recallAt8 minimum, median and maximum Recall@8 across the runs.
 * @param medianRunIndex the index into the variant's own list of runs (not exposed on this record —
 *     only {@link MultiRunAggregator} needs the raw list) whose nDCG@8 is the median value; that
 *     run is what {@link VariantOutcome#report()} returns for a multi-run outcome, so every
 *     per-case delta and every other field of the report is the median run's, not an average.
 * @param decompositionDeviatingCaseCount how many golden cases got a different ordered list of
 *     sub-queries from decomposition in at least one of the runs, compared to the first run's list
 *     for that case ({@link MultiRunAggregator#summarize}'s {@code List#equals} comparison is
 *     order-sensitive) — the specification's own "eigentliche Kennzahl der Instabilität", more
 *     informative than any spread on the metrics themselves. Deliberately conservative: a run that
 *     produced the identical sub-queries in a different order counts as deviating too, since a
 *     reordering can still change which candidates {@link
 *     io.opaa.query.QueryService#retrieveRelevantChunksInGivenScopeWithDecomposition} pools first
 *     for Reciprocal Rank Fusion.
 * @param decompositionDeviatingCaseIds the ids of those cases, worst-informative first is not
 *     defined here (unlike {@link VariantReport.CaseDelta}, there is no single ordering metric for
 *     "how different" a sub-query list is) — kept in dataset order instead.
 */
public record MultiRunSummary(
    int runCount,
    MetricRange hitRateAt5,
    MetricRange mrrAt8,
    MetricRange ndcgAt8,
    MetricRange recallAt8,
    int medianRunIndex,
    int decompositionDeviatingCaseCount,
    List<String> decompositionDeviatingCaseIds) {

  /** Minimum, median and maximum of one metric across a variant's repeated runs. */
  public record MetricRange(double min, double median, double max) {}
}
