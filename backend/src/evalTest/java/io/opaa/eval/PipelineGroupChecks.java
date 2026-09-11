package io.opaa.eval;

import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * The five metric checks a committed baseline of a {@link PipelineMetricsAggregate}-shaped group is
 * judged by, in one place for every path that measures at this window (issue #1484): which metric
 * carries which hit count, which one gets the hard floor, and that {@code n_eff} comes from the
 * baseline's {@code distinctExpectedDocumentSets} rather than from the case count.
 *
 * <p>The error criterion itself is ADR-0013's and lives in {@link BaselineComparator#metricCheck} -
 * this class only decides which checks a group produces, which is precisely the part two
 * comparators had begun to carry twice.
 */
final class PipelineGroupChecks {

  private PipelineGroupChecks() {}

  /**
   * @param applyHardFloor whether this group additionally has to clear the baseline-independent
   *     floor - true for {@code overall} only, which is the group that would notice an empty vector
   *     store.
   * @param missingGroupHint what a reader has to do when the baseline lacks this group; appended to
   *     the failure, because the two paths reach that state for different reasons.
   */
  static void checkGroup(
      List<BaselineComparator.MetricCheck> checks,
      String groupKey,
      PipelineMetricsAggregate current,
      Map<String, PipelineMetricsAggregate> baselineGroups,
      boolean applyHardFloor,
      String missingGroupHint) {
    PipelineMetricsAggregate base = baselineGroups.get(groupKey);
    if (base == null) {
      throw new IllegalStateException(
          "Baseline has no entry for group '" + groupKey + "' — " + missingGroupHint);
    }
    int nEff = base.distinctExpectedDocumentSets();
    addCheck(
        checks,
        groupKey,
        "hitRateAt5",
        base.hitRateAt5(),
        current.hitRateAt5(),
        nEff,
        applyHardFloor ? PipelineBaselineComparator.HARD_FLOOR_ABSOLUTE_HIT_RATE : null,
        PipelineMetricsAggregate::hitCountAt5,
        base,
        current);
    addCheck(
        checks,
        groupKey,
        "mrrAt8",
        base.mrrAt8(),
        current.mrrAt8(),
        nEff,
        applyHardFloor ? PipelineBaselineComparator.HARD_FLOOR_ABSOLUTE_MRR : null,
        PipelineMetricsAggregate::hitCountAt8,
        base,
        current);
    addCheck(
        checks,
        groupKey,
        "ndcgAt8",
        base.ndcgAt8(),
        current.ndcgAt8(),
        nEff,
        applyHardFloor ? PipelineBaselineComparator.HARD_FLOOR_ABSOLUTE_NDCG : null,
        PipelineMetricsAggregate::hitCountAt8,
        base,
        current);
    addCheck(
        checks,
        groupKey,
        "recallAt8",
        base.recallAt8(),
        current.recallAt8(),
        nEff,
        applyHardFloor ? PipelineBaselineComparator.HARD_FLOOR_ABSOLUTE_RECALL : null,
        PipelineMetricsAggregate::hitCountAt8,
        base,
        current);
    addCheck(
        checks,
        groupKey,
        "allExpectedDocumentsHitAt8",
        base.allExpectedDocumentsHitAt8(),
        current.allExpectedDocumentsHitAt8(),
        nEff,
        null,
        PipelineMetricsAggregate::hitCountAt8,
        base,
        current);
  }

  private static void addCheck(
      List<BaselineComparator.MetricCheck> checks,
      String group,
      String metric,
      double baselineValue,
      double currentValue,
      int nEff,
      Double absoluteHardFloor,
      ToIntFunction<PipelineMetricsAggregate> hitCountFn,
      PipelineMetricsAggregate base,
      PipelineMetricsAggregate current) {
    checks.add(
        BaselineComparator.metricCheck(
            group,
            metric,
            current.n(),
            baselineValue,
            currentValue,
            nEff,
            absoluteHardFloor,
            base.n(),
            hitCountFn.applyAsInt(base),
            hitCountFn.applyAsInt(current)));
  }
}
