package io.opaa.eval;

import io.opaa.eval.PipelineEvaluationReport.PipelineQueryResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a variant's repeated pipeline runs into a {@link MultiRunSummary} (issue #1044,
 * docs/features/retrieval-benchmark.md §3). The only caller is {@link VariantRunner}; kept as its
 * own, Docker-free unit so the aggregation math is exercised without a real {@code QueryService}
 * (see {@code MultiRunAggregatorTest}).
 */
final class MultiRunAggregator {

  /**
   * "Solche Varianten laufen dreimal" (docs/features/retrieval-benchmark.md §3, "Was stattdessen
   * gilt", 2.) — not a tunable, a specification decision. A different number is a specification
   * change, not a configuration change.
   */
  static final int DECOMPOSITION_RUN_COUNT = 3;

  private MultiRunAggregator() {}

  /**
   * @param runs the variant's repeated {@link PipelineEvaluationReport}s, in run order. Every run
   *     must have evaluated exactly the same golden cases (guaranteed by {@link VariantRunner}
   *     passing the identical {@code goldenCases} list to every run) — a case id present in one run
   *     and absent from another would make "did decomposition deviate for this case" undefined, and
   *     is therefore rejected rather than silently ignored.
   */
  static MultiRunSummary summarize(List<PipelineEvaluationReport> runs) {
    if (runs.size() < 2) {
      throw new IllegalArgumentException(
          "MultiRunAggregator.summarize requires at least two runs to compute a spread, got "
              + runs.size()
              + " — a single run has nothing to aggregate and does not belong here (see "
              + "VariantPrerequisites/VariantRunner for the run-count decision).");
    }

    List<Double> hitRateAt5 = new ArrayList<>(runs.size());
    List<Double> mrrAt8 = new ArrayList<>(runs.size());
    List<Double> ndcgAt8 = new ArrayList<>(runs.size());
    List<Double> recallAt8 = new ArrayList<>(runs.size());
    for (PipelineEvaluationReport run : runs) {
      hitRateAt5.add(run.overall().hitRateAt5());
      mrrAt8.add(run.overall().mrrAt8());
      ndcgAt8.add(run.overall().ndcgAt8());
      recallAt8.add(run.overall().recallAt8());
    }

    int medianRunIndex = medianIndexByNdcg(ndcgAt8);

    // LinkedHashMap: deviating case ids are reported in the first run's dataset order, matching
    // how every other per-case list in this codebase orders itself.
    Map<String, List<String>> subQueriesByCaseIdInFirstRun = new LinkedHashMap<>();
    for (PipelineQueryResult result : runs.get(0).allQueryResults()) {
      subQueriesByCaseIdInFirstRun.put(result.id(), result.subQueries());
    }

    List<String> deviatingCaseIds = new ArrayList<>();
    for (Map.Entry<String, List<String>> entry : subQueriesByCaseIdInFirstRun.entrySet()) {
      String caseId = entry.getKey();
      List<String> firstRunSubQueries = entry.getValue();
      boolean deviates = false;
      for (int i = 1; i < runs.size(); i++) {
        List<String> subQueries = subQueriesForCase(runs.get(i), caseId);
        if (!subQueries.equals(firstRunSubQueries)) {
          deviates = true;
          break;
        }
      }
      if (deviates) {
        deviatingCaseIds.add(caseId);
      }
    }

    return new MultiRunSummary(
        runs.size(),
        range(hitRateAt5),
        range(mrrAt8),
        range(ndcgAt8),
        range(recallAt8),
        medianRunIndex,
        deviatingCaseIds.size(),
        List.copyOf(deviatingCaseIds));
  }

  private static List<String> subQueriesForCase(PipelineEvaluationReport run, String caseId) {
    return run.allQueryResults().stream()
        .filter(r -> r.id().equals(caseId))
        .findFirst()
        .map(PipelineQueryResult::subQueries)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Case '"
                        + caseId
                        + "' appears in one multi-run report but not another — every run of the"
                        + " same variant must evaluate the identical golden dataset"
                        + " (docs/features/retrieval-benchmark.md, Abschnitt 2, \"gepaart"
                        + " messen\")."));
  }

  /**
   * The run whose nDCG@8 is the median value — nDCG@8 is the finest-grained of the four metrics
   * (docs/features/retrieval-benchmark.md, Teil 0), so it is the tie-breaking choice among the four
   * possible "median run" candidates when the odd-length {@link #DECOMPOSITION_RUN_COUNT} runs
   * don't already agree on which run is the middle one for every metric at once.
   */
  private static int medianIndexByNdcg(List<Double> ndcgAt8) {
    List<Integer> indicesByValue = new ArrayList<>();
    for (int i = 0; i < ndcgAt8.size(); i++) {
      indicesByValue.add(i);
    }
    indicesByValue.sort((a, b) -> Double.compare(ndcgAt8.get(a), ndcgAt8.get(b)));
    return indicesByValue.get(indicesByValue.size() / 2);
  }

  private static MultiRunSummary.MetricRange range(List<Double> values) {
    List<Double> sorted = new ArrayList<>(values);
    sorted.sort(Double::compare);
    double min = sorted.get(0);
    double max = sorted.get(sorted.size() - 1);
    double median = median(sorted);
    return new MultiRunSummary.MetricRange(min, median, max);
  }

  /** {@code sorted} must already be sorted ascending. Even-length lists average the two middles. */
  private static double median(List<Double> sorted) {
    int n = sorted.size();
    if (n % 2 == 1) {
      return sorted.get(n / 2);
    }
    return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
  }
}
