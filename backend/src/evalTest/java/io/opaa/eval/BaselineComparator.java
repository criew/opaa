package io.opaa.eval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares a freshly produced {@link EvaluationReport} against the committed {@link Baseline}
 * (issue #228). Two independent questions are answered, on purpose kept separate rather than
 * collapsed into a single pass/fail bit — conflating them is exactly the failure mode the issue
 * warns about ("Baseline ungültig" vs. "Retrieval ist schlechter geworden" are different
 * statements):
 *
 * <ol>
 *   <li>{@link ComparisonResult#baselineValid()} — do the fixed points (measurement-contract
 *       version, corpus manifest, golden dataset, embedding model digest, chunk size, {@code
 *       searchTopK}, {@code pgvectorIndexType}, embedding dimensions, production similarity
 *       threshold) still match what the baseline was measured under? If not, no metric comparison
 *       is meaningful and none is attempted.
 *   <li>Only if the baseline is valid: does every group's metrics stay within tolerance of the
 *       baseline, and do the four overall metrics clear a hard floor combining a baseline-relative
 *       and a fixed absolute component (see {@link #HARD_FLOOR_FRACTION_OF_BASELINE})?
 * </ol>
 *
 * <h2>Tolerance formula (ADR-0013)</h2>
 *
 * The tolerance policy in this class implements ADR-0013's decision, not an ad hoc formula: it is a
 * project-level statement of what "no regression" means, and any change to it is an ADR change, not
 * a code review comment. See {@code docs/decisions/0013-fehlerkriterium-retrieval-regression.md}
 * for the full rationale (including the PR #301 review this superseded). Summary:
 *
 * <pre>
 *   tolerance = min( K_MIN / n_eff, RELATIVE_CAP_FRACTION * baselineValue )
 * </pre>
 *
 * <ul>
 *   <li>{@code K_MIN / n_eff} ("case-based" term) expresses the tolerance as "how many independent
 *       cases would have to flip to call this a regression" rather than as a fixed metric-point
 *       amount. {@code n_eff} is {@link MetricsAggregate#distinctExpectedDocumentSets()} — the
 *       number of *distinct* expected-document sets in the group, not the raw case count — because
 *       several golden-dataset cases share an identical expected set (see that field's Javadoc), so
 *       the raw case count overstates how many independent observations back the group's average.
 *   <li>{@code RELATIVE_CAP_FRACTION * baselineValue} ("relative cap") additionally bounds the
 *       case-based term for low-scoring groups: without it, a small {@code n_eff} would license an
 *       arbitrarily large *relative* drop on a near-zero baseline (e.g. {@code numeric_range}'s
 *       nDCG@10 of 0.063) purely because the group is small — exactly the failure mode ADR-0013
 *       replaces. The cap can only tighten the tolerance, never loosen it.
 * </ul>
 *
 * There is deliberately no separate absolute floor or cap term for the *tolerance* (unlike the
 * formula this replaced): ADR-0013 found that a single absolute bound binds at both ends of the
 * score range at once — too loose for weak, low-{@code n} groups and, simultaneously, exactly at
 * the one-case boundary for others. Expressing the tolerance in cases and combining it with a
 * relative (not absolute) cap avoids that collision. (The hard *floor* below is a separate concern
 * and does combine a relative and an absolute term — see {@link #HARD_FLOOR_FRACTION_OF_BASELINE}.)
 *
 * <p>ADR-0013's own "Offen" section acknowledges a residual gap: whenever a group/metric pair's
 * tolerance is tighter than the shift a single case can cause ({@code 1/n}), one case flipping can
 * still fail that pair even though nothing changed elsewhere. As measured against the current
 * baseline this affects six pairs, not one, all in the two weakest categories:
 *
 * <table>
 *   <caption>Pairs where tolerance &lt; one case's worth of shift (1/n)</caption>
 *   <tr><th>Group / metric</th><th>Tolerance</th><th>1/n</th><th>Ratio</th></tr>
 *   <tr><td>{@code numeric_range} / {@code recallAt10}</td><td>0.0150</td><td>0.0625</td><td>0.24</td></tr>
 *   <tr><td>{@code numeric_range} / {@code ndcgAt10}</td><td>0.0158</td><td>0.0625</td><td>0.25</td></tr>
 *   <tr><td>{@code numeric_range} / {@code mrr}</td><td>0.0253</td><td>0.0625</td><td>0.40</td></tr>
 *   <tr><td>{@code multi_attribute_filter} / {@code ndcgAt10}</td><td>0.0343</td><td>0.0476</td><td>0.72</td></tr>
 *   <tr><td>{@code numeric_range} / {@code hitRateAt5}</td><td>0.0470</td><td>0.0625</td><td>0.75</td></tr>
 *   <tr><td>{@code multi_attribute_filter} / {@code recallAt10}</td><td>0.0398</td><td>0.0476</td><td>0.83</td></tr>
 * </table>
 *
 * For {@code numeric_range}'s {@code ndcgAt10} it is enough for a single one of the 16 queries to
 * drop from rank 1 to rank 3 — no lost hit required — to breach the tolerance more than twice over.
 * This is a known, deliberately deferred gap (tracked as issue #306, from ADR-0013's "Offen"
 * section), not silently claimed as solved here: a case-count-based check (e.g. "the number of
 * cases with {@code ndcgAt10 > 0} may drop by at most one") would close it exactly, without needing
 * more calibration evidence, but is out of scope for this class.
 *
 * <p>The downgrade of this gap from "must-fix before merge" to "tracked follow-up" rests on four
 * bit-identical {@code checkRetrievalBaseline} runs across three different machines reproducing
 * these exact tolerance-vs-one-case numbers with zero delta in the affected groups — see ADR-0013's
 * Nachtrag. That evidence is real but comes entirely from developer/reviewer machines; the first
 * scheduled run on GitHub Actions' own runner hardware is the actual first test of whether this
 * tightness holds outside that sample.
 *
 * <p>Comparisons apply a small epsilon (see {@link #EPSILON}) so a boundary case that is
 * mathematically exactly on the tolerance line does not fail on floating-point rounding alone (PR
 * #301 review: {@code category:entity_description}'s {@code hitRateAt5} landed on {@code 12/20 −
 * 0.65 = −0.05000000000000004} against a tolerance of exactly {@code 0.05}).
 */
public final class BaselineComparator {

  /** Two independent cases flipping is treated as noise; a third is a finding (ADR-0013). */
  static final double K_MIN = 2.0;

  /** No group/metric tolerates more than a 25% relative drop from its baseline (ADR-0013). */
  static final double RELATIVE_CAP_FRACTION = 0.25;

  /**
   * Baseline-relative component of the hard floor applied only to the four overall (micro-averaged)
   * metrics — see class Javadoc and ADR-0013, decision 4, plus the correction recorded in the ADR's
   * Nachtrag from the second PR #301 review round.
   *
   * <p><b>Deliberately combined with {@link #HARD_FLOOR_ABSOLUTE_HIT_RATE} et al. via {@code
   * max(...)}, not used alone.</b> A purely baseline-relative floor turned out to be two different
   * things pretending to be one: at {@code 0.8 * baselineValue} it sits *above* the primary
   * tolerance for every overall metric (tolerance ≈ 0.021 vs. a floor gap of 0.2 * baselineValue ≈
   * 0.089–0.104), so it could never fire while the baseline is valid — the tolerance check always
   * triggers first. And because it scales with whatever baseline is currently committed, it tracks
   * a baseline down if that baseline itself erodes over several PRs, instead of anchoring against
   * that erosion — the opposite of what a "hard floor" is for. A fixed absolute floor alone has the
   * opposite problem (it erodes to irrelevance as scores improve over time, per the original PR
   * #301 review). Combining both via {@code max} gives each term the job it can actually do: the
   * relative term still matters once a future baseline is measured much lower than today's, the
   * absolute term is the anchor that does not move when the baseline file itself is changed.
   */
  static final double HARD_FLOOR_FRACTION_OF_BASELINE = 0.8;

  /**
   * Fixed, baseline-independent floors for the four overall metrics — the values this class used
   * exclusively before the (reverted) all-relative attempt above. See {@link
   * #HARD_FLOOR_FRACTION_OF_BASELINE} for why they are combined with, not replaced by, the relative
   * term.
   */
  static final double HARD_FLOOR_ABSOLUTE_HIT_RATE = 0.30;

  static final double HARD_FLOOR_ABSOLUTE_MRR = 0.25;
  static final double HARD_FLOOR_ABSOLUTE_NDCG = 0.25;
  static final double HARD_FLOOR_ABSOLUTE_RECALL = 0.25;

  /**
   * Epsilon for the boundary comparison — see class Javadoc, last paragraph. Deliberately much
   * smaller than any tolerance value in use (smallest is on the order of 1e-2), so it only absorbs
   * floating-point noise and never meaningfully widens the tolerance itself.
   */
  static final double EPSILON = 1e-9;

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
      Set<String> visitedGroups = new LinkedHashSet<>();

      checkGroup(checks, Baseline.OVERALL, report.overall(), baseline.groups(), true);
      visitedGroups.add(Baseline.OVERALL);

      report
          .byCategory()
          .forEach(
              (name, agg) -> {
                String key = Baseline.category(name);
                checkGroup(checks, key, agg, baseline.groups(), false);
                visitedGroups.add(key);
              });
      report
          .byDifficulty()
          .forEach(
              (name, agg) -> {
                String key = Baseline.difficulty(name);
                checkGroup(checks, key, agg, baseline.groups(), false);
                visitedGroups.add(key);
              });
      report
          .byLanguage()
          .forEach(
              (name, agg) -> {
                String key = Baseline.language(name);
                checkGroup(checks, key, agg, baseline.groups(), false);
                visitedGroups.add(key);
              });

      // Symmetric check (PR #301 review): the loops above only ever notice a group the *report*
      // has that the baseline lacks (via the throw in checkGroup). A report whose byCategory (or
      // byDifficulty/byLanguage) map came back empty or partial — a harness bug, not a legitimate
      // measurement — would otherwise silently run only the four overall checks and pass. Since
      // group names come from golden-dataset content and the golden dataset's hash is itself a
      // fixed point checked above, a mismatch here is only possible via a bug, never a legitimate
      // corpus/dataset change — so it is a hard failure, not a tolerance case.
      Set<String> missingFromReport = new TreeSet<>(baseline.groups().keySet());
      missingFromReport.removeAll(visitedGroups);
      if (!missingFromReport.isEmpty()) {
        throw new IllegalStateException(
            "The report is missing group(s) the baseline expects: "
                + missingFromReport
                + ". The golden-dataset hash matched the baseline, so these categories/"
                + "difficulties/languages must exist in the golden dataset — this indicates a "
                + "harness bug (e.g. an incompletely populated report), not a legitimate change, "
                + "and is therefore not treated as a tolerance case.");
      }
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
        mismatches,
        "embeddingDimensions",
        String.valueOf(fp.embeddingDimensions()),
        String.valueOf(cfg.embeddingDimensions()));
    addIfDiffers(
        mismatches, "chunkSize", String.valueOf(fp.chunkSize()), String.valueOf(cfg.chunkSize()));
    addIfDiffers(
        mismatches,
        "chunkSizeMatchesApplicationDefault",
        String.valueOf(fp.chunkSizeMatchesApplicationDefault()),
        String.valueOf(cfg.chunkSizeMatchesApplicationDefault()));
    // ADR-0012, decision 3: searchTopK=10 and the deliberate omission of the production similarity
    // threshold are both part of the measurement contract (PR #301 review, Befund 4) — a change to
    // either would silently change what every metric means, not just how well retrieval scored.
    addIfDiffers(
        mismatches,
        "searchTopK",
        String.valueOf(fp.searchTopK()),
        String.valueOf(cfg.searchTopK()));
    addIfDiffers(
        mismatches,
        "productionSimilarityThreshold",
        String.valueOf(fp.productionSimilarityThreshold()),
        String.valueOf(cfg.productionSimilarityThreshold()));
    // ADR-0011, Konsequenzen: switching the evaluation to exact search instead of HNSW is a
    // foreseen future change to the measurement itself, not a retrieval-quality change.
    addIfDiffers(mismatches, "pgvectorIndexType", fp.pgvectorIndexType(), cfg.pgvectorIndexType());
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
      Map<String, MetricsAggregate> baselineGroups,
      boolean applyHardFloor) {
    MetricsAggregate base = baselineGroups.get(groupKey);
    if (base == null) {
      throw new IllegalStateException(
          "Baseline has no entry for group '"
              + groupKey
              + "' — the golden dataset gained a new category/difficulty/language value without a "
              + "baseline re-measurement (see eval/baseline/README.md).");
    }
    int nEff = base.distinctExpectedDocumentSets();
    addMetricCheck(
        checks,
        groupKey,
        "hitRateAt5",
        current.n(),
        base.hitRateAt5(),
        current.hitRateAt5(),
        nEff,
        applyHardFloor ? HARD_FLOOR_ABSOLUTE_HIT_RATE : null);
    addMetricCheck(
        checks,
        groupKey,
        "mrr",
        current.n(),
        base.mrr(),
        current.mrr(),
        nEff,
        applyHardFloor ? HARD_FLOOR_ABSOLUTE_MRR : null);
    addMetricCheck(
        checks,
        groupKey,
        "ndcgAt10",
        current.n(),
        base.ndcgAt10(),
        current.ndcgAt10(),
        nEff,
        applyHardFloor ? HARD_FLOOR_ABSOLUTE_NDCG : null);
    addMetricCheck(
        checks,
        groupKey,
        "recallAt10",
        current.n(),
        base.recallAt10(),
        current.recallAt10(),
        nEff,
        applyHardFloor ? HARD_FLOOR_ABSOLUTE_RECALL : null);
  }

  private static void addMetricCheck(
      List<MetricCheck> checks,
      String group,
      String metric,
      int n,
      double baselineValue,
      double currentValue,
      int nEff,
      Double absoluteHardFloor) {
    double delta = currentValue - baselineValue;
    double tolerance = toleranceFor(baselineValue, nEff);
    boolean withinTolerance = delta >= -tolerance - EPSILON;
    // ADR-0013 Nachtrag (second PR #301 review round): the hard floor combines a baseline-relative
    // component with a fixed absolute one via max(...) — see HARD_FLOOR_FRACTION_OF_BASELINE's
    // Javadoc for why neither alone is sufficient.
    double hardFloor =
        absoluteHardFloor == null
            ? Double.NEGATIVE_INFINITY
            : Math.max(HARD_FLOOR_FRACTION_OF_BASELINE * baselineValue, absoluteHardFloor);
    boolean passesHardFloor = currentValue >= hardFloor - EPSILON;
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

  /** The tolerance formula documented in the class Javadoc (ADR-0013), exposed for unit testing. */
  static double toleranceFor(double baselineValue, int nEff) {
    double caseBased = K_MIN / Math.max(nEff, 1);
    double relativeCap = RELATIVE_CAP_FRACTION * baselineValue;
    return Math.min(caseBased, relativeCap);
  }
}
