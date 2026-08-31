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
 * Compares a freshly produced {@link PipelineEvaluationReport} against the committed {@link
 * PipelineBaseline} (issue #1040) — the pipeline path's counterpart of {@link BaselineComparator},
 * answering the same two deliberately separate questions ("is the baseline still valid?" and only
 * then "did retrieval regress?").
 *
 * <p><b>The error criterion is ADR-0013's, unchanged and literally shared.</b> Every metric check
 * here is produced by {@link BaselineComparator#metricCheck} — the same tolerance formula, the same
 * case-based conjunction from issue #306, the same hard-floor combination. Nothing about "what
 * counts as a regression" is redefined for this path; what differs is only <i>what is measured</i>
 * (the pipeline's @8 window with the similarity threshold applied) and <i>which fixed points make a
 * baseline valid</i>.
 *
 * <p><b>Own fixed points, including the five that were previously only reported.</b> ADR-0012's
 * Nachtrag zum Pipeline-Messpfad listed {@code fetch-k}, {@code similarity-threshold}, {@code
 * max-chunks-per-document}, {@code mmr-lambda} and {@code max-sub-queries} as fixed points but left
 * them unchecked, explicitly because "es gibt nichts, wogegen verglichen würde" — that reason ends
 * with the first committed pipeline baseline, so they are validity fields here (ADR-0012, Nachtrag
 * Pipeline-Baselines, decision 18). An {@code mmr-lambda} change now invalidates the baseline
 * loudly instead of quietly changing what its numbers describe.
 *
 * <p><b>No chunk-count invariant here.</b> The pipeline path measures the very index the raw-vector
 * path just measured, whose harness asserts that invariant before any report is written (ADR-0010);
 * re-asserting it would report the same violation twice under two verdicts.
 */
public final class PipelineBaselineComparator {

  /**
   * Fixed, baseline-independent floors for the four overall metrics of this path, in the role
   * {@link BaselineComparator#HARD_FLOOR_ABSOLUTE_HIT_RATE} et al. play for the raw-vector path:
   * the second, baseline-independent net against catastrophic failure (an empty or misconfigured
   * vector store), not a quality target.
   *
   * <p><b>Own values, deliberately lower than the raw-vector path's.</b> ADR-0013's absolute floors
   * were calibrated against @10 measurements taken without a similarity threshold. This path
   * applies the production threshold, so a document can leave the ranking entirely rather than fall
   * back, and it scores at a narrower window — its numbers lie systematically lower for reasons
   * that have nothing to do with retrieval quality (ADR-0012, Nachtrag, decision 12). Carrying the
   * raw path's numbers over would either fire on a healthy run or, if the pipeline scored higher
   * than expected, anchor at an arbitrary point; the values below are anchored at half of
   * ADR-0013's respective floors, which is far under any plausible healthy measurement of this path
   * and still far above the "vector store returned nothing" case the floor exists for.
   */
  static final double HARD_FLOOR_ABSOLUTE_HIT_RATE = 0.15;

  static final double HARD_FLOOR_ABSOLUTE_MRR = 0.125;
  static final double HARD_FLOOR_ABSOLUTE_NDCG = 0.125;
  static final double HARD_FLOOR_ABSOLUTE_RECALL = 0.125;

  private PipelineBaselineComparator() {}

  /**
   * Same shape as {@link BaselineComparator.ComparisonResult} minus the chunk-count invariant (see
   * the class Javadoc), so both paths' results render through the same reporting vocabulary.
   */
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

  public static ComparisonResult compare(
      PipelineBaseline baseline, PipelineEvaluationReport report) {
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
                // Issue #304: language:de is the redundant twin of category:crosslingual and is
                // redundant for the same construction reason on this path — skip it exactly while
                // the baseline lacks an entry for it, self-healing like the raw path's skip.
                if (key.equals(BaselineComparator.REDUNDANT_LANGUAGE_GROUP)
                    && !baseline.groups().containsKey(key)) {
                  return;
                }
                checkGroup(checks, key, agg, baseline.groups(), false);
                visitedGroups.add(key);
              });

      // Symmetric check, same reasoning as BaselineComparator's: the loops above only notice a
      // group the report has and the baseline lacks. A report with an empty or partial group map
      // would otherwise run four overall checks and pass.
      Set<String> missingFromReport = new TreeSet<>(baseline.groups().keySet());
      missingFromReport.removeAll(visitedGroups);
      if (!missingFromReport.isEmpty()) {
        throw new IllegalStateException(
            "The pipeline report is missing group(s) the baseline expects: "
                + missingFromReport
                + ". The golden-dataset hash matched the baseline, so these categories/"
                + "difficulties/languages must exist in the golden dataset — this indicates a "
                + "harness bug (e.g. an incompletely populated report), not a legitimate change, "
                + "and is therefore not treated as a tolerance case.");
      }
    }

    return new ComparisonResult(baselineValid, mismatches, List.copyOf(checks));
  }

  private static List<BaselineComparator.FixedPointMismatch> fixedPointMismatches(
      PipelineBaseline baseline, PipelineEvaluationReport report) {
    List<BaselineComparator.FixedPointMismatch> mismatches = new ArrayList<>();
    var fp = baseline.fixedPoints();
    var cfg = report.runConfiguration();

    addIfDiffers(
        mismatches,
        "pipelineMeasurementContractVersion",
        String.valueOf(baseline.pipelineMeasurementContractVersion()),
        String.valueOf(report.pipelineMeasurementContractVersion()));
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
    addIfDiffers(
        mismatches,
        "chunkOverlap",
        String.valueOf(fp.chunkOverlap()),
        String.valueOf(cfg.chunkOverlap()));
    // ADR-0012, Nachtrag, decisions 13 and 18: the production query parameters of this path. All
    // five below were reported but unchecked until this baseline existed.
    addIfDiffers(mismatches, "fetchK", String.valueOf(fp.fetchK()), String.valueOf(cfg.fetchK()));
    addIfDiffers(mismatches, "topK", String.valueOf(fp.topK()), String.valueOf(cfg.topK()));
    addIfDiffers(
        mismatches,
        "similarityThreshold",
        String.valueOf(fp.similarityThreshold()),
        String.valueOf(cfg.similarityThreshold()));
    addIfDiffers(
        mismatches,
        "maxChunksPerDocument",
        String.valueOf(fp.maxChunksPerDocument()),
        String.valueOf(cfg.maxChunksPerDocument()));
    addIfDiffers(
        mismatches, "mmrLambda", String.valueOf(fp.mmrLambda()), String.valueOf(cfg.mmrLambda()));
    addIfDiffers(
        mismatches,
        "queryDecompositionEnabled",
        String.valueOf(fp.queryDecompositionEnabled()),
        String.valueOf(cfg.queryDecompositionEnabled()));
    addIfDiffers(
        mismatches,
        "maxSubQueries",
        String.valueOf(fp.maxSubQueries()),
        String.valueOf(cfg.maxSubQueries()));
    // Null on both sides while the decomposition-off variant is measured; a model appearing on
    // either side alone means the two runs did not measure the same thing.
    addIfDiffers(mismatches, "chatModel", fp.chatModel(), cfg.chatModel());
    // The two windows the metric names state literally (ADR-0012, Nachtrag, decision 12).
    addIfDiffers(
        mismatches, "hitRateK", String.valueOf(fp.hitRateK()), String.valueOf(cfg.hitRateK()));
    addIfDiffers(
        mismatches, "rankingK", String.valueOf(fp.rankingK()), String.valueOf(cfg.rankingK()));
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
          "Pipeline baseline has no entry for group '"
              + groupKey
              + "' — the golden dataset gained a new category/difficulty/language value without a "
              + "baseline re-measurement (see eval/baseline/README.md).");
    }
    int nEff = base.distinctExpectedDocumentSets();
    addCheck(
        checks,
        groupKey,
        "hitRateAt5",
        base.hitRateAt5(),
        current.hitRateAt5(),
        nEff,
        applyHardFloor ? HARD_FLOOR_ABSOLUTE_HIT_RATE : null,
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
        applyHardFloor ? HARD_FLOOR_ABSOLUTE_MRR : null,
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
        applyHardFloor ? HARD_FLOOR_ABSOLUTE_NDCG : null,
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
        applyHardFloor ? HARD_FLOOR_ABSOLUTE_RECALL : null,
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
