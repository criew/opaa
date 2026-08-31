package io.opaa.eval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.ToIntFunction;

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
 * <h2>Case-based check for pairs where the mean tolerance is tighter than one case (issue #306)
 * </h2>
 *
 * ADR-0013's "Offen" section originally acknowledged a residual gap: whenever a group/metric pair's
 * mean tolerance ({@link #toleranceFor}) is tighter than the shift a single case can cause ({@code
 * 1/n}), one case flipping could fail that pair even though nothing changed elsewhere. As measured
 * against the baseline current as of issue #306, this affects six pairs, all in the two weakest
 * categories:
 *
 * <table>
 *   <caption>Pairs where the mean tolerance &lt; one case's worth of shift (1/n)</caption>
 *   <tr><th>Group / metric</th><th>Tolerance</th><th>1/n</th><th>Ratio</th></tr>
 *   <tr><td>{@code numeric_range} / {@code recallAt10}</td><td>0.0150</td><td>0.0625</td><td>0.24</td></tr>
 *   <tr><td>{@code numeric_range} / {@code ndcgAt10}</td><td>0.0158</td><td>0.0625</td><td>0.25</td></tr>
 *   <tr><td>{@code numeric_range} / {@code mrr}</td><td>0.0253</td><td>0.0625</td><td>0.40</td></tr>
 *   <tr><td>{@code multi_attribute_filter} / {@code ndcgAt10}</td><td>0.0343</td><td>0.0476</td><td>0.72</td></tr>
 *   <tr><td>{@code numeric_range} / {@code hitRateAt5}</td><td>0.0470</td><td>0.0625</td><td>0.75</td></tr>
 *   <tr><td>{@code multi_attribute_filter} / {@code recallAt10}</td><td>0.0398</td><td>0.0476</td><td>0.83</td></tr>
 * </table>
 *
 * For {@code numeric_range}'s {@code ndcgAt10} it was enough for a single one of the 16 queries to
 * drop from rank 1 to rank 3 — no lost hit required — to breach the mean tolerance more than twice
 * over.
 *
 * <p><b>Fix (issue #306, ADR-0013 Nachtrag):</b> {@link #metricCheck} determines, per group/metric
 * pair and dynamically (not from a hardcoded pair list — see {@link #usesCaseBasedCheck}), whether
 * {@code toleranceFor(baselineValue, nEff) < 1.0 / n}. Where that holds, the pair must clear
 * <b>both</b> of the following (a conjunction, not a replacement — see "Review correction" below):
 *
 * <ol>
 *   <li>A case-count test: the number of cases in the group scoring above zero ({@link
 *       MetricsAggregate#hitCountAt5()} for {@code hitRateAt5}; {@link
 *       MetricsAggregate#hitCountAt10()} for {@code mrr}/{@code ndcgAt10}/{@code recallAt10} — the
 *       same per-case event for all three, see {@code MetricsAggregate}'s Javadoc) must not drop by
 *       more than {@link #MAX_CASE_COUNT_DROP} compared to the baseline's own recorded count for
 *       that group/metric. This is exactly the check ADR-0013's "Offen" section proposed ("die Zahl
 *       der Fälle mit {@code ndcgAt10 > 0} darf um höchstens 1 sinken").
 *   <li>A mean-tolerance test, using {@code toleranceFor(...)} <b>widened</b> to at least {@code
 *       1/n} ({@code Math.max(toleranceFor(baselineValue, nEff), 1.0 / n)}) — never narrower than
 *       the unwidened tolerance, only ever loosened up to one case's worth of shift.
 * </ol>
 *
 * <p><b>Review correction (issue #306 review):</b> the version of this fix first proposed for issue
 * #306 <i>replaced</i> the mean-tolerance test with the case-count test for these six pairs,
 * dropping {@code toleranceFor(...)}'s 25%-relative-cap protection entirely. That protected against
 * lost hits but not against a same-hit-count regression that is nevertheless severe — confirmed
 * against this baseline's real per-case data: {@code numeric_range}'s {@code mrr} can drop 75% (all
 * four hitting cases sliding to rank 10, no hit lost, {@code hitCountAt10} unchanged) and would
 * have passed under the replaced version. The conjunction above closes that gap while still passing
 * the original issue #306 scenario (one case moving rank with no lost hit, shift {@code 0.5/16 =
 * 0.03125 < 1/16 = 0.0625}) — the widened tolerance is loose enough for a single case's worth of
 * shift, but the un-widened {@code toleranceFor(...)} term inside the {@code max(...)} still
 * catches anything worse for pairs whose baseline tolerance is not much smaller than {@code 1/n},
 * and the widened floor itself catches the {@code mrr}/{@code recallAt10} scenarios above (0.076
 * and 0.061, respectively, both {@code > 1/n}).
 *
 * <p>This needs the baseline to carry {@code hitCountAt5}/{@code hitCountAt10} per group — a small,
 * one-time schema addition, not a new calibration exercise: both counts are derived from the exact
 * same {@link EvaluationReport#allQueryResults()} that already produced every other number in the
 * baseline, so populating them requires no new measurement, only reading a number that was already
 * implicitly present in the run that produced the current baseline. The counts used for {@code
 * eval/baseline/comic-characters.json} were read from the byte-identical, artifact-verified {@code
 * checkRetrievalBaseline} run on GitHub Actions' own runner hardware referenced in ADR-0013's
 * Nachtrag (run id {@code 32442551477}, commit {@code 45faad2}) — not invented or back-calculated
 * from the committed means, which (see below) would not have been possible exactly.
 *
 * <p><b>Rejected alternative: derive the historical count from the baseline's already-committed
 * mean and {@code n}</b> (e.g. {@code round(baselineValue * n)}), avoiding the schema addition
 * entirely. Set aside because it is not exact for any metric except {@code hitRateAt5}: {@code
 * hitRateAt5} is binary per case (its mean times {@code n} is exactly the hit count), but {@code
 * ndcgAt10}/{@code recallAt10}/{@code mrr} are continuous per case (a hit ranked lower than 1st
 * scores less than 1.0), so their means understate the true count of cases with a hit — confirmed
 * against the real {@code numeric_range}/{@code multi_attribute_filter} per-case data behind the
 * current baseline (e.g. {@code numeric_range}'s {@code ndcgAt10} mean of 0.063 sums to {@code
 * 1.008}, not the actual hit count of 4). A count derived that way would not be the "number of
 * cases with {@code ndcgAt10 > 0}" ADR-0013 asked for, and would silently under-protect these pairs
 * rather than over-protect them.
 *
 * <p>The dynamic {@code toleranceFor(...) < 1/n} condition — rather than a hardcoded six-pair list
 * — means this also self-adjusts: a future baseline re-measurement that shifts a pair's tolerance
 * or {@code n} picks up or drops the case-based check for that pair automatically, the same
 * self-healing spirit as the {@code language:de} skip below.
 *
 * <p>The evidence that the *mean*-tolerance formula itself is tight enough to need this fix rests
 * on four bit-identical {@code checkRetrievalBaseline} runs across three different machines
 * reproducing these exact tolerance-vs-one-case numbers with zero delta in the affected groups —
 * see ADR-0013's Nachtrag. That evidence is real but comes entirely from developer/reviewer
 * machines; the scheduled run on GitHub Actions' own runner hardware referenced above is the first
 * confirmation that this tightness holds outside that sample.
 *
 * <p>Comparisons apply a small epsilon (see {@link #EPSILON}) so a boundary case that is
 * mathematically exactly on the tolerance line does not fail on floating-point rounding alone (PR
 * #301 review: {@code category:entity_description}'s {@code hitRateAt5} landed on {@code 12/20 −
 * 0.65 = −0.05000000000000004} against a tolerance of exactly {@code 0.05}).
 */
public final class BaselineComparator {

  /**
   * The golden dataset's {@code crosslingual} category is, by construction, exactly the set of
   * {@code language:de} cases (issue #304): every {@code crosslingual} case is a German question
   * against the English corpus, and there is no other source of German cases. The two groups are
   * therefore never independent observations — comparing both doubles the number of checks over
   * identical data without adding coverage. ADR-0013 ("Offen") and {@code eval/baseline/README.md}
   * record the consolidation decision: keep {@code category:crosslingual} (it names the property
   * under test), drop {@code language:de} as a baseline/comparison group. The report still computes
   * a {@code language:de} entry from the (unchanged) golden dataset — this constant lets {@link
   * #compare} skip just that one redundant group instead of requiring a golden-dataset or harness
   * change.
   *
   * <p><b>Self-healing (PR #673 review):</b> {@link #compare} only skips {@code language:de} while
   * the baseline actually lacks an entry for it. If a future baseline re-measurement legitimately
   * reintroduces a {@code language:de} group (e.g. once the golden dataset gains German cases that
   * are not simply {@code crosslingual}'s twins), the skip stops applying on its own and the group
   * is compared like any other — it is never silently discarded once the baseline tracks it again.
   */
  static final String REDUNDANT_LANGUAGE_GROUP = Baseline.language("de");

  /** Two independent cases flipping is treated as noise; a third is a finding (ADR-0013). */
  static final double K_MIN = 2.0;

  /**
   * Issue #306: for group/metric pairs whose mean tolerance is tighter than one case's worth of
   * shift ({@code 1/n}), the case-count check (see class Javadoc) tolerates the success count
   * dropping by at most this many cases — one flipped case is noise, two is a finding. Kept at 1,
   * not {@link #K_MIN}'s 2: the case-count check already uses the *actual* per-case hit/miss
   * outcome rather than the mean-tolerance formula's coarser case-based term, so it does not need
   * the same margin to avoid the boundary-rounding failure mode {@link #K_MIN} was raised to 2 for
   * (PR #301 review, {@code category:entity_description}) — an exact integer comparison has no
   * floating-point boundary to begin with.
   */
  static final int MAX_CASE_COUNT_DROP = 1;

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
      boolean passesHardFloor,
      // Issue #306 (review Befund 1 — conjunction, not replacement): true when this pair's mean
      // tolerance was tighter than one case's worth of shift (1/n) and the case-count check (see
      // BaselineComparator's class Javadoc) was *additionally* required, on top of the
      // mean-tolerance test. tolerance() above always holds the effective mean tolerance actually
      // applied — for a case-based pair that is max(meanTolerance, 1/n), not meanTolerance alone.
      boolean caseBasedCheck) {

    public boolean passed() {
      return withinTolerance && passesHardFloor;
    }

    @Override
    public String toString() {
      return String.format(
          Locale.ROOT,
          "%-24s %-10s Baseline=%.3f Ist=%.3f Delta=%+.3f Toleranz=%.3f%s%s%s",
          group,
          metric,
          baselineValue,
          currentValue,
          delta,
          tolerance,
          caseBasedCheck ? " [+fallzahlbasiert]" : "",
          withinTolerance ? "" : " [TOLERANZ VERLETZT]",
          passesHardFloor ? "" : " [UNTERGRENZE VERLETZT]");
    }
  }

  public record ComparisonResult(
      boolean baselineValid,
      List<FixedPointMismatch> fixedPointMismatches,
      boolean chunkCountInvariantHolds,
      List<EvaluationReport.ChunkCountInvariantResult.Violation> chunkCountInvariantViolations,
      List<MetricCheck> checks) {

    public boolean passed() {
      return baselineValid
          && chunkCountInvariantHolds
          && checks.stream().allMatch(MetricCheck::passed);
    }

    public List<MetricCheck> failedChecks() {
      return checks.stream().filter(c -> !c.passed()).toList();
    }
  }

  /**
   * A report measured against an external Ollama endpoint (issue #1076, {@code
   * opaa.eval.ollamaBaseUrl}) is never baseline-comparable — CPU/GPU embedding kernels are not
   * guaranteed bit-identical, analogous to the {@code -Dopaa.eval.allowGpu} opt-out. Called by
   * every {@code *BaselineRegressionTest} before {@link #compare(Baseline, EvaluationReport)}, so a
   * stray external run fails loudly instead of being silently compared as if it were a
   * Testcontainer/CPU run.
   */
  public static void requireBaselineComparable(EvaluationReport report) {
    if (report.runConfiguration().externalOllamaEndpoint()) {
      throw new IllegalStateException(
          "Report stammt von einem externen Ollama-Endpunkt (opaa.eval.ollamaBaseUrl) — nicht "
              + "baseline-vergleichbar. Siehe eval/README.md, \"Externer Ollama-Endpunkt\".");
    }
  }

  public static ComparisonResult compare(Baseline baseline, EvaluationReport report) {
    List<FixedPointMismatch> mismatches = fixedPointMismatches(baseline, report);
    boolean baselineValid = mismatches.isEmpty();
    var invariant = report.chunkCountInvariant();

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
                // Issue #304: language:de is the redundant twin of category:crosslingual (see
                // REDUNDANT_LANGUAGE_GROUP's Javadoc) and, today, has no baseline entry — skip it
                // rather than comparing against a group the baseline deliberately no longer
                // carries. Self-healing (PR #673 review): only skip while the baseline actually
                // lacks the entry. Should a future baseline re-measurement add a genuine
                // language:de group back (e.g. once the golden dataset gains German cases that are
                // not simply the crosslingual twins), this check falls through to the normal
                // comparison instead of silently discarding it.
                if (key.equals(REDUNDANT_LANGUAGE_GROUP) && !baseline.groups().containsKey(key)) {
                  return;
                }
                checkGroup(checks, key, agg, baseline.groups(), false);
                visitedGroups.add(key);
              });

      // Symmetric check (PR #301 review): the loops above only ever notice a group the *report*
      // has that the baseline lacks (via the throw in checkGroup). A report whose byCategory (or
      // byDifficulty/byLanguage) map came back empty or partial — a harness bug, not a legitimate
      // measurement — would otherwise silently run only the four overall checks and pass. Since
      // group names come from golden-dataset content and the golden dataset's hash is itself a
      // fixed point checked above, a mismatch here is only possible via a bug, never a legitimate
      // corpus/dataset change — so it is a hard failure, not a tolerance case. language:de is
      // deliberately excluded above (issue #304) and must not trip this check.
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
    // Issue #721, ADR-0012 Nachtrag: chunkOverlap and the document-bound k-window are now
    // measurement-contract fixed points — see FixedPoints' Javadoc for why.
    addIfDiffers(
        mismatches,
        "chunkOverlap",
        String.valueOf(fp.chunkOverlap()),
        String.valueOf(cfg.chunkOverlap()));
    addIfDiffers(
        mismatches,
        "documentTopK",
        String.valueOf(fp.documentTopK()),
        String.valueOf(cfg.documentTopK()));
    addIfDiffers(
        mismatches, "chunkTopK", String.valueOf(fp.chunkTopK()), String.valueOf(cfg.chunkTopK()));
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
        applyHardFloor ? HARD_FLOOR_ABSOLUTE_HIT_RATE : null,
        MetricsAggregate::hitCountAt5,
        base,
        current);
    addMetricCheck(
        checks,
        groupKey,
        "mrr",
        current.n(),
        base.mrr(),
        current.mrr(),
        nEff,
        applyHardFloor ? HARD_FLOOR_ABSOLUTE_MRR : null,
        MetricsAggregate::hitCountAt10,
        base,
        current);
    addMetricCheck(
        checks,
        groupKey,
        "ndcgAt10",
        current.n(),
        base.ndcgAt10(),
        current.ndcgAt10(),
        nEff,
        applyHardFloor ? HARD_FLOOR_ABSOLUTE_NDCG : null,
        MetricsAggregate::hitCountAt10,
        base,
        current);
    addMetricCheck(
        checks,
        groupKey,
        "recallAt10",
        current.n(),
        base.recallAt10(),
        current.recallAt10(),
        nEff,
        applyHardFloor ? HARD_FLOOR_ABSOLUTE_RECALL : null,
        MetricsAggregate::hitCountAt10,
        base,
        current);
    // Issue #913 review: allExpectedDocumentsHitAt10 ("Recall pro Teilthema") was measured but not
    // guarded — losing one of two expected documents only drops recallAt10 to 0.5, still within
    // tolerance. No hard floor (not one of ADR-0013's four overall metrics); hitCountAt10 is a
    // valid
    // hit-count proxy here too, same reasoning as for mrr/ndcgAt10/recallAt10 above.
    addMetricCheck(
        checks,
        groupKey,
        "allExpectedDocumentsHitAt10",
        current.n(),
        base.allExpectedDocumentsHitAt10(),
        current.allExpectedDocumentsHitAt10(),
        nEff,
        null,
        MetricsAggregate::hitCountAt10,
        base,
        current);
  }

  private static void addMetricCheck(
      List<MetricCheck> checks,
      String group,
      String metric,
      int n,
      double baselineValue,
      double currentValue,
      int nEff,
      Double absoluteHardFloor,
      ToIntFunction<MetricsAggregate> hitCountFn,
      MetricsAggregate base,
      MetricsAggregate current) {
    checks.add(
        metricCheck(
            group,
            metric,
            n,
            baselineValue,
            currentValue,
            nEff,
            absoluteHardFloor,
            base.n(),
            hitCountFn.applyAsInt(base),
            hitCountFn.applyAsInt(current)));
  }

  /**
   * ADR-0013's error criterion itself, independent of which path's aggregate the numbers came from
   * — tolerance formula, case-based conjunction, and hard floor in one place. Issue #1040 gave the
   * pipeline path its own baselines; sharing this method rather than copying it is what makes
   * "beide Pfade nach dem unveränderten Fehlerkriterium aus ADR-0013" a structural fact instead of
   * a claim two implementations could drift apart on.
   *
   * @param baseN the baseline's own, frozen case count — used for both the {@code 1/n} switch
   *     condition and the widened tolerance (issue #306 review, Befund 4): the baseline is what the
   *     tolerance and "affected pair" status are defined against, and the two sides must not
   *     silently mix.
   * @param absoluteHardFloor {@code null} for a metric without a hard floor (every group other than
   *     {@code overall}, and {@code allExpectedDocumentsHit…} everywhere).
   */
  static MetricCheck metricCheck(
      String group,
      String metric,
      int n,
      double baselineValue,
      double currentValue,
      int nEff,
      Double absoluteHardFloor,
      int baseN,
      int baselineHitCount,
      int currentHitCount) {
    double delta = currentValue - baselineValue;
    double meanTolerance = toleranceFor(baselineValue, nEff);
    boolean caseBasedCheck = usesCaseBasedCheck(meanTolerance, baseN);
    // Issue #306 review, Befund 1: the case-count check alone caught only *lost hits*, not a
    // severe rank or partial-recall degradation that leaves hitCountAt5/hitCountAt10 unchanged
    // (verified against this baseline's real per-case data: numeric_range's mrr can drop 75% —
    // all four hits sliding from their current ranks to rank 10 — without losing a single hit).
    // Replacing the mean-tolerance test outright therefore silently gave up the 25%-relative-cap
    // protection for exactly the six affected pairs. Fix: a conjunction, not a replacement — both
    // the case-count check *and* a mean check must pass, the latter with its tolerance widened to
    // at least one case's worth of shift (1/n) so the original issue #306 scenario (one case
    // moving rank with no lost hit) still passes, while a same-hit-count-but-worse-ranked or
    // partial-recall regression like the mrr example above (Δ ≈ 0.076 > 1/16 ≈ 0.0625) is caught.
    double tolerance = caseBasedCheck ? Math.max(meanTolerance, 1.0 / baseN) : meanTolerance;
    boolean meanWithinTolerance = delta >= -tolerance - EPSILON;
    boolean withinTolerance;
    if (caseBasedCheck) {
      boolean caseCountWithinTolerance = currentHitCount >= baselineHitCount - MAX_CASE_COUNT_DROP;
      withinTolerance = caseCountWithinTolerance && meanWithinTolerance;
    } else {
      withinTolerance = meanWithinTolerance;
    }
    // ADR-0013 Nachtrag (second PR #301 review round): the hard floor combines a baseline-relative
    // component with a fixed absolute one via max(...) — see HARD_FLOOR_FRACTION_OF_BASELINE's
    // Javadoc for why neither alone is sufficient.
    double hardFloor =
        absoluteHardFloor == null
            ? Double.NEGATIVE_INFINITY
            : Math.max(HARD_FLOOR_FRACTION_OF_BASELINE * baselineValue, absoluteHardFloor);
    boolean passesHardFloor = currentValue >= hardFloor - EPSILON;
    return new MetricCheck(
        group,
        metric,
        n,
        baselineValue,
        currentValue,
        delta,
        tolerance,
        withinTolerance,
        hardFloor,
        passesHardFloor,
        caseBasedCheck);
  }

  /** The tolerance formula documented in the class Javadoc (ADR-0013), exposed for unit testing. */
  static double toleranceFor(double baselineValue, int nEff) {
    double caseBased = K_MIN / Math.max(nEff, 1);
    double relativeCap = RELATIVE_CAP_FRACTION * baselineValue;
    return Math.min(caseBased, relativeCap);
  }

  /**
   * Issue #306: whether {@code meanTolerance} is tighter than the shift one flipped case causes
   * ({@code 1/n}) — the condition under which {@link #metricCheck} additionally requires the
   * case-count test (see class Javadoc) to pass, on top of the (widened) mean-tolerance test.
   * Deliberately computed from the *current* {@code toleranceFor(...)} result and a raw case count
   * rather than a hardcoded pair list, so it self-adjusts to whichever pairs qualify under a future
   * baseline re-measurement — see the class Javadoc. The case count passed in is always the
   * baseline's own {@code n} (issue #306 review, Befund 4) — see the {@code baseN} parameter of
   * {@link #metricCheck} — matching the "1/n" wording in ADR-0013's "Offen" section and README
   * table: it is the raw case count the *baseline's* mean was actually divided by.
   */
  static boolean usesCaseBasedCheck(double meanTolerance, int n) {
    return n > 0 && meanTolerance < (1.0 / n) - EPSILON;
  }
}
