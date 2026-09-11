package io.opaa.eval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.ToIntFunction;

/**
 * Compares a freshly produced {@link ConversationEvaluationReport} against the committed {@link
 * ConversationBaseline} (issue #1484) - the multi-turn path's counterpart of {@link
 * PipelineBaselineComparator}, answering the same two deliberately separate questions ("is the
 * baseline still valid?" and only then "did retrieval regress?").
 *
 * <p><b>Nothing about the error criterion is redefined here.</b> Every metric check is produced by
 * {@link BaselineComparator#metricCheck} and every pipeline fixed point by {@link
 * PipelineBaselineComparator#addPipelineFixedPointMismatches}; what this path adds is the three
 * conversation-memory fixed points and the two groupings it is judged by - per case class and
 * <b>per turn</b>. The per-turn groups are the point: a change that helps standalone first turns
 * and hurts follow-ups leaves the overall aggregate flat.
 *
 * <p>The hard floors are the pipeline path's, unchanged: a multi-turn run measures at the identical
 * windows with the identical threshold applied, so "the vector store returned nothing" looks the
 * same here.
 */
public final class ConversationBaselineComparator {

  private ConversationBaselineComparator() {}

  /** Same shape as {@link PipelineBaselineComparator.ComparisonResult}. */
  public record ComparisonResult(
      boolean baselineValid,
      List<BaselineComparator.FixedPointMismatch> fixedPointMismatches,
      List<BaselineComparator.MetricCheck> checks) {

    public boolean passed() {
      return baselineValid && checks.stream().allMatch(BaselineComparator.MetricCheck::passed);
    }

    public List<BaselineComparator.MetricCheck> failedChecks() {
      return checks.stream().filter(c -> !c.passed()).toList();
    }
  }

  /**
   * A report measured against an external Ollama endpoint is never baseline-comparable - same
   * reasoning as {@link PipelineBaselineComparator#requireBaselineComparable}, same underlying run.
   */
  public static void requireBaselineComparable(ConversationEvaluationReport report) {
    if (report.runConfiguration().pipeline().externalOllamaEndpoint()) {
      throw new IllegalStateException(
          "Mehrrunden-Report stammt von einem externen Ollama-Endpunkt (opaa.eval.ollamaBaseUrl) — "
              + "nicht baseline-vergleichbar. Siehe eval/README.md, \"Externer Ollama-Endpunkt\".");
    }
  }

  public static ComparisonResult compare(
      ConversationBaseline baseline, ConversationEvaluationReport report) {
    List<BaselineComparator.FixedPointMismatch> mismatches = fixedPointMismatches(baseline, report);
    boolean baselineValid = mismatches.isEmpty();

    List<BaselineComparator.MetricCheck> checks = new ArrayList<>();
    if (baselineValid) {
      Set<String> visitedGroups = new LinkedHashSet<>();

      checkGroup(checks, Baseline.OVERALL, report.overall(), baseline.groups(), true);
      visitedGroups.add(Baseline.OVERALL);

      report
          .byCategory()
          .forEach(
              (name, aggregate) -> {
                String key = Baseline.category(name);
                checkGroup(checks, key, aggregate, baseline.groups(), false);
                visitedGroups.add(key);
              });
      report
          .byTurn()
          .forEach(
              (name, aggregate) -> {
                String key = ConversationBaseline.turn(name);
                checkGroup(checks, key, aggregate, baseline.groups(), false);
                visitedGroups.add(key);
              });

      // Symmetric check, same reasoning as both other comparators': the loops above only notice a
      // group the report has and the baseline lacks.
      Set<String> missingFromReport = new TreeSet<>(baseline.groups().keySet());
      missingFromReport.removeAll(visitedGroups);
      if (!missingFromReport.isEmpty()) {
        throw new IllegalStateException(
            "The conversation report is missing group(s) the baseline expects: "
                + missingFromReport
                + ". The dataset hash matched the baseline, so these classes/turn numbers must "
                + "exist in it — this indicates a harness bug (e.g. an incompletely populated "
                + "report), not a legitimate change, and is therefore not treated as a tolerance "
                + "case.");
      }
    }

    return new ComparisonResult(baselineValid, mismatches, List.copyOf(checks));
  }

  private static List<BaselineComparator.FixedPointMismatch> fixedPointMismatches(
      ConversationBaseline baseline, ConversationEvaluationReport report) {
    List<BaselineComparator.FixedPointMismatch> mismatches = new ArrayList<>();
    var fixedPoints = baseline.fixedPoints();
    var cfg = report.runConfiguration();

    addIfDiffers(
        mismatches,
        "conversationMeasurementContractVersion",
        String.valueOf(baseline.conversationMeasurementContractVersion()),
        String.valueOf(report.conversationMeasurementContractVersion()));
    PipelineBaselineComparator.addPipelineFixedPointMismatches(
        mismatches, fixedPoints.pipeline(), cfg.pipeline());
    // docs/features/conversation-memory.md: each of the three moves what the decomposition sees.
    addIfDiffers(
        mismatches,
        "conversationWindowMessages",
        String.valueOf(fixedPoints.conversationWindowMessages()),
        String.valueOf(cfg.memoryProfile().windowMessages()));
    addIfDiffers(
        mismatches,
        "searchWindowTurns",
        String.valueOf(fixedPoints.searchWindowTurns()),
        String.valueOf(cfg.memoryProfile().searchWindowTurns()));
    addIfDiffers(
        mismatches,
        "conversationNoteCap",
        String.valueOf(fixedPoints.conversationNoteCap()),
        String.valueOf(cfg.memoryProfile().noteCap()));
    // The second half of the dataset's size: a curation round that only lengthens cases leaves
    // goldenCaseCount untouched while changing what is measured.
    addIfDiffers(
        mismatches,
        "turnCount",
        String.valueOf(fixedPoints.turnCount()),
        String.valueOf(cfg.turnCount()));
    return List.copyOf(mismatches);
  }

  private static void addIfDiffers(
      List<BaselineComparator.FixedPointMismatch> mismatches,
      String field,
      String baselineValue,
      String currentValue) {
    if (!Objects.equals(baselineValue, currentValue)) {
      mismatches.add(new BaselineComparator.FixedPointMismatch(field, baselineValue, currentValue));
    }
  }

  private static void checkGroup(
      List<BaselineComparator.MetricCheck> checks,
      String groupKey,
      PipelineMetricsAggregate current,
      Map<String, PipelineMetricsAggregate> baselineGroups,
      boolean applyHardFloor) {
    PipelineMetricsAggregate base = baselineGroups.get(groupKey);
    if (base == null) {
      throw new IllegalStateException(
          "Conversation baseline has no entry for group '"
              + groupKey
              + "' — the dataset gained a new case class or a longer case without a baseline "
              + "re-measurement (see eval/baseline/README.md).");
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
