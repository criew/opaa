package io.opaa.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compares a freshly produced {@link EvaluationReport} against the committed {@link Baseline}
 * (issue #228). Two independent questions are answered, on purpose kept separate rather than
 * collapsed into a single pass/fail bit — conflating them is exactly the failure mode the issue
 * warns about ("Baseline ungültig" vs. "Retrieval ist schlechter geworden" are different
 * statements):
 *
 * <ol>
 *   <li>{@link ComparisonResult#baselineValid()} — do the fixed points (measurement-contract
 *       version, corpus manifest, golden dataset, embedding model digest, chunk size) still match
 *       what the baseline was measured under? If not, no metric comparison is meaningful and none
 *       is attempted.
 *   <li>Only if the baseline is valid: does every group's metrics stay within tolerance of the
 *       baseline, and do the four overall metrics clear an independent, baseline-agnostic hard
 *       floor?
 * </ol>
 *
 * <h2>Tolerance rationale (see eval/baseline/README.md for the full writeup)</h2>
 *
 * A single absolute tolerance across all groups does not work: 0.05 is noise against {@code
 * attribute_lookup}'s baseline of ~0.94 but is nearly the entire signal for {@code numeric_range}'s
 * baseline of ~0.06 (see the #228 issue description). A single relative tolerance does not work
 * either — a relative fraction of a near-zero baseline collapses to a near-zero tolerance, so the
 * smallest legitimate fluctuation would fail the group. This harness therefore combines three terms
 * per group and metric:
 *
 * <pre>
 *   tolerance = clamp(
 *       max(RELATIVE_FRACTION * baselineValue, ONE_CASE_GUARD_FRACTION / n, ABSOLUTE_FLOOR),
 *       upperBound = ABSOLUTE_CAP)
 * </pre>
 *
 * <ul>
 *   <li>{@code RELATIVE_FRACTION * baselineValue} scales the tolerance to the group's own score
 *       level — the dominant term for mid/high-scoring groups (attribute_lookup,
 *       entity_description, easy).
 *   <li>{@code ONE_CASE_GUARD_FRACTION / n} scales inversely with group size, per the #228
 *       instruction that small groups (numeric_range, n=16) fluctuate more per case than large ones
 *       and must get a correspondingly larger tolerance, not a smaller one. It becomes the dominant
 *       term for exactly the small, low-scoring groups where the relative term alone would collapse
 *       to near zero.
 *   <li>{@code ABSOLUTE_FLOOR} is a minimum floor so no group — however large and however
 *       high-scoring — ever gets an unreasonably tight tolerance from the other two terms alone.
 *   <li>{@code ABSOLUTE_CAP} bounds the tolerance from above so a high baseline value cannot
 *       license an arbitrarily large drop before the job notices.
 * </ul>
 *
 * The three independent, byte-identical measurement runs recorded in the #228 PR (two by the
 * author, one by the reviewer on different hardware) justify tolerances this tight: the HNSW
 * approximation risk ADR-0011 calls out does not manifest at this corpus size, so a wider margin
 * would only let real regressions through without buying additional stability.
 */
public final class BaselineComparator {

  static final double RELATIVE_FRACTION = 0.12;
  static final double ONE_CASE_GUARD_FRACTION = 1.0;
  static final double ABSOLUTE_FLOOR = 0.02;
  static final double ABSOLUTE_CAP = 0.05;

  /**
   * Independent, baseline-agnostic sanity bound applied only to the four overall (micro-averaged)
   * metrics — see class Javadoc. Deliberately far below the current baseline (Hit@5=0.521,
   * MRR=0.461, nDCG@10=0.445, Recall@10=0.490): the baseline-relative tolerance above is expected
   * to catch real regressions long before these floors would trigger. Their purpose is to catch
   * catastrophic breakage (e.g. an empty or misconfigured vector store) that would still need to be
   * caught even if the baseline file itself were somehow wrong.
   */
  static final double HARD_FLOOR_HIT_RATE = 0.30;

  static final double HARD_FLOOR_MRR = 0.25;
  static final double HARD_FLOOR_NDCG = 0.25;
  static final double HARD_FLOOR_RECALL = 0.25;

  private BaselineComparator() {}

  public record FixedPointMismatch(String field, String baselineValue, String currentValue) {
    @Override
    public String toString() {
      return String.format(
          Locale.ROOT, "%s: Baseline=%s, aktuell=%s", field, baselineValue, currentValue);
    }
  }

  public record MetricCheck(
      String group,
      String metric,
      int n,
      double baselineValue,
      double currentValue,
      double delta,
      double tolerance,
      boolean withinTolerance,
      double hardFloor,
      boolean passesHardFloor) {

    public boolean passed() {
      return withinTolerance && passesHardFloor;
    }

    @Override
    public String toString() {
      return String.format(
          Locale.ROOT,
          "%-24s %-10s Baseline=%.3f Ist=%.3f Delta=%+.3f Toleranz=%.3f%s%s",
          group,
          metric,
          baselineValue,
          currentValue,
          delta,
          tolerance,
          withinTolerance ? "" : " [TOLERANZ VERLETZT]",
          passesHardFloor ? "" : " [UNTERGRENZE VERLETZT]");
    }
  }

  public record ComparisonResult(
      boolean baselineValid,
      List<FixedPointMismatch> fixedPointMismatches,
      boolean oneChunkInvariantHolds,
      List<EvaluationReport.OneChunkInvariantResult.Violation> oneChunkInvariantViolations,
      List<MetricCheck> checks) {

    public boolean passed() {
      return baselineValid
          && oneChunkInvariantHolds
          && checks.stream().allMatch(MetricCheck::passed);
    }

    public List<MetricCheck> failedChecks() {
      return checks.stream().filter(c -> !c.passed()).toList();
    }
  }

  public static ComparisonResult compare(Baseline baseline, EvaluationReport report) {
    List<FixedPointMismatch> mismatches = fixedPointMismatches(baseline, report);
    boolean baselineValid = mismatches.isEmpty();
    var invariant = report.oneChunkInvariant();

    List<MetricCheck> checks = new ArrayList<>();
    if (baselineValid) {
      checkGroup(checks, Baseline.OVERALL, report.overall(), baseline.groups(), true);
      report
          .byCategory()
          .forEach(
              (name, agg) ->
                  checkGroup(checks, Baseline.category(name), agg, baseline.groups(), false));
      report
          .byDifficulty()
          .forEach(
              (name, agg) ->
                  checkGroup(checks, Baseline.difficulty(name), agg, baseline.groups(), false));
      report
          .byLanguage()
          .forEach(
              (name, agg) ->
                  checkGroup(checks, Baseline.language(name), agg, baseline.groups(), false));
    }

    return new ComparisonResult(
        baselineValid, mismatches, invariant.holds(), invariant.violations(), checks);
  }

  private static List<FixedPointMismatch> fixedPointMismatches(
      Baseline baseline, EvaluationReport report) {
    List<FixedPointMismatch> mismatches = new ArrayList<>();
    var fp = baseline.fixedPoints();
    var cfg = report.runConfiguration();

    addIfDiffers(
        mismatches,
        "measurementContractVersion",
        String.valueOf(baseline.measurementContractVersion()),
        String.valueOf(report.measurementContractVersion()));
    addIfDiffers(mismatches, "embeddingModel", fp.embeddingModel(), cfg.embeddingModel());
    addIfDiffers(
        mismatches, "embeddingModelDigest", fp.embeddingModelDigest(), cfg.embeddingModelDigest());
    addIfDiffers(
        mismatches, "chunkSize", String.valueOf(fp.chunkSize()), String.valueOf(cfg.chunkSize()));
    addIfDiffers(
        mismatches,
        "chunkSizeMatchesApplicationDefault",
        String.valueOf(fp.chunkSizeMatchesApplicationDefault()),
        String.valueOf(cfg.chunkSizeMatchesApplicationDefault()));
    addIfDiffers(
        mismatches, "corpusManifestSha256", fp.corpusManifestSha256(), cfg.corpusManifestSha256());
    addIfDiffers(
        mismatches,
        "corpusDocumentCount",
        String.valueOf(fp.corpusDocumentCount()),
        String.valueOf(cfg.corpusDocumentCount()));
    addIfDiffers(mismatches, "goldenDatasetFile", fp.goldenDatasetFile(), cfg.goldenDatasetFile());
    addIfDiffers(
        mismatches, "goldenDatasetSha256", fp.goldenDatasetSha256(), cfg.goldenDatasetSha256());
    addIfDiffers(
        mismatches,
        "goldenCaseCount",
        String.valueOf(fp.goldenCaseCount()),
        String.valueOf(cfg.goldenCaseCount()));
    return List.copyOf(mismatches);
  }

  private static void addIfDiffers(
      List<FixedPointMismatch> mismatches,
      String field,
      String baselineValue,
      String currentValue) {
    if (!java.util.Objects.equals(baselineValue, currentValue)) {
      mismatches.add(new FixedPointMismatch(field, baselineValue, currentValue));
    }
  }

  private static void checkGroup(
      List<MetricCheck> checks,
      String groupKey,
      MetricsAggregate current,
      java.util.Map<String, MetricsAggregate> baselineGroups,
      boolean applyHardFloor) {
    MetricsAggregate base = baselineGroups.get(groupKey);
    if (base == null) {
      throw new IllegalStateException(
          "Baseline has no entry for group '"
              + groupKey
              + "' — the golden dataset gained a new category/difficulty/language value without a "
              + "baseline re-measurement (see eval/baseline/README.md).");
    }
    addMetricCheck(
        checks,
        groupKey,
        "hitRateAt5",
        current.n(),
        base.hitRateAt5(),
        current.hitRateAt5(),
        applyHardFloor ? HARD_FLOOR_HIT_RATE : Double.NEGATIVE_INFINITY);
    addMetricCheck(
        checks,
        groupKey,
        "mrr",
        current.n(),
        base.mrr(),
        current.mrr(),
        applyHardFloor ? HARD_FLOOR_MRR : Double.NEGATIVE_INFINITY);
    addMetricCheck(
        checks,
        groupKey,
        "ndcgAt10",
        current.n(),
        base.ndcgAt10(),
        current.ndcgAt10(),
        applyHardFloor ? HARD_FLOOR_NDCG : Double.NEGATIVE_INFINITY);
    addMetricCheck(
        checks,
        groupKey,
        "recallAt10",
        current.n(),
        base.recallAt10(),
        current.recallAt10(),
        applyHardFloor ? HARD_FLOOR_RECALL : Double.NEGATIVE_INFINITY);
  }

  private static void addMetricCheck(
      List<MetricCheck> checks,
      String group,
      String metric,
      int n,
      double baselineValue,
      double currentValue,
      double hardFloor) {
    double delta = currentValue - baselineValue;
    double tolerance = toleranceFor(baselineValue, n);
    boolean withinTolerance = delta >= -tolerance;
    boolean passesHardFloor = currentValue >= hardFloor;
    checks.add(
        new MetricCheck(
            group,
            metric,
            n,
            baselineValue,
            currentValue,
            delta,
            tolerance,
            withinTolerance,
            hardFloor,
            passesHardFloor));
  }

  /** The tolerance formula documented in the class Javadoc, exposed for unit testing. */
  static double toleranceFor(double baselineValue, int n) {
    double relative = RELATIVE_FRACTION * baselineValue;
    double oneCaseGuard = n <= 0 ? ABSOLUTE_CAP : ONE_CASE_GUARD_FRACTION / n;
    double raw = Math.max(relative, Math.max(oneCaseGuard, ABSOLUTE_FLOOR));
    return Math.min(raw, ABSOLUTE_CAP);
  }
}
