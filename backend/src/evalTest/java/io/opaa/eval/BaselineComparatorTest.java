package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.opaa.eval.EvaluationReport.OneChunkInvariantResult;
import io.opaa.eval.EvaluationReport.RunConfiguration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Docker-free unit tests for the tolerance formula (ADR-0013) and fixed-point comparison in {@link
 * BaselineComparator} — see its Javadoc for the rationale. Part of {@code evalUnitTest}, wired into
 * {@code check}.
 */
class BaselineComparatorTest {

  @Test
  void toleranceIsDominatedByTheCaseBasedTermForModerateNAndHighBaselineValues() {
    // attribute_lookup-like: n_eff=30, ndcg=0.942 → case-based (2/30=0.0667) beats the relative cap
    // (0.25*0.942=0.2355).
    assertThat(BaselineComparator.toleranceFor(0.942, 30)).isEqualTo(2.0 / 30, within(1e-9));
  }

  @Test
  void relativeCapDominatesForLowBaselineValuesEvenWithSmallGroups() {
    // numeric_range-like: n_eff=15, ndcg=0.063 → the case-based term alone (2/15=0.1333) would
    // license a ~211% relative drop on this tiny baseline; the relative cap (0.25*0.063=0.01575)
    // reins that in — this is the exact scenario ADR-0013 decision 3 exists for.
    assertThat(BaselineComparator.toleranceFor(0.063, 15)).isEqualTo(0.25 * 0.063, within(1e-9));
  }

  @Test
  void relativeCapNeverWidensTheCaseBasedTerm() {
    // A very small n_eff with a very high baseline value: case-based (2/5=0.4) would exceed 100%
    // of the baseline value; the relative cap (0.25*0.9=0.225) still wins, keeping the tolerance
    // sane regardless of how small the group is.
    assertThat(BaselineComparator.toleranceFor(0.9, 5)).isEqualTo(0.225, within(1e-9));
  }

  @Test
  void oneCaseFlipNoLongerFalselyFailsTheEntityDescriptionBoundaryCase() {
    // PR #301 review: with the old formula, 12/20 - 0.65 == -0.05000000000000004 against a
    // tolerance of exactly 0.05 failed on floating-point rounding alone. The new tolerance
    // (2/20=0.1) comfortably covers a single case flip (1/20=0.05) with margin to spare, and the
    // epsilon in addMetricCheck absorbs any remaining boundary rounding.
    double tolerance = BaselineComparator.toleranceFor(0.650, 20);
    assertThat(tolerance).isEqualTo(0.1, within(1e-9));
    assertThat(tolerance).isGreaterThan(1.0 / 20);
  }

  @Test
  void detectsCorpusManifestDrift() {
    Baseline baseline = baselineWith(fixedPoints("m1", "d1", "corpus-a", "golden-a"));
    EvaluationReport report = reportWith(runConfiguration("m1", "d1", "corpus-b", "golden-a"));

    var result = BaselineComparator.compare(baseline, report);

    assertThat(result.baselineValid()).isFalse();
    assertThat(result.fixedPointMismatches())
        .extracting(BaselineComparator.FixedPointMismatch::field)
        .containsExactly("corpusManifestSha256");
    // No metric checks are attempted once the baseline is invalid — comparing scores under a
    // different corpus would not be a "regression", it would be a meaningless number.
    assertThat(result.checks()).isEmpty();
    assertThat(result.passed()).isFalse();
  }

  @Test
  void detectsEmbeddingModelDigestDrift() {
    Baseline baseline = baselineWith(fixedPoints("m1", "d1", "corpus-a", "golden-a"));
    EvaluationReport report = reportWith(runConfiguration("m1", "d2", "corpus-a", "golden-a"));

    var result = BaselineComparator.compare(baseline, report);

    assertThat(result.baselineValid()).isFalse();
    assertThat(result.fixedPointMismatches())
        .extracting(BaselineComparator.FixedPointMismatch::field)
        .containsExactly("embeddingModelDigest");
  }

  @Test
  void detectsSearchTopKDrift() {
    // ADR-0012 decision 3 / PR #301 review, Befund 4: searchTopK is part of the measurement
    // contract, not just run metadata — a topK change must invalidate the baseline, not be silently
    // compared as if it were a regression.
    Baseline baseline = baselineWith(fixedPoints("m1", "d1", "corpus-a", "golden-a"));
    RunConfiguration cfg = runConfiguration("m1", "d1", "corpus-a", "golden-a");
    RunConfiguration withDifferentTopK =
        new RunConfiguration(
            cfg.embeddingProvider(),
            cfg.embeddingModel(),
            cfg.embeddingModelDigest(),
            cfg.ollamaImage(),
            cfg.embeddingDimensions(),
            cfg.chunkSize(),
            cfg.chunkSizeMatchesApplicationDefault(),
            50,
            cfg.productionSimilarityThreshold(),
            cfg.similarityThresholdNote(),
            cfg.pgvectorIndexType(),
            cfg.corpusManifestSha256(),
            cfg.corpusDocumentCount(),
            cfg.goldenDatasetFile(),
            cfg.goldenDatasetSha256(),
            cfg.goldenCaseCount(),
            cfg.runStartedAt(),
            cfg.runDurationSeconds());
    EvaluationReport report = reportWith(withDifferentTopK);

    var result = BaselineComparator.compare(baseline, report);

    assertThat(result.baselineValid()).isFalse();
    assertThat(result.fixedPointMismatches())
        .extracting(BaselineComparator.FixedPointMismatch::field)
        .containsExactly("searchTopK");
  }

  @Test
  void passesWhenFixedPointsMatchAndMetricsAreWithinTolerance() {
    Baseline baseline = baselineWith(fixedPoints("m1", "d1", "corpus-a", "golden-a"));
    EvaluationReport report = reportWith(runConfiguration("m1", "d1", "corpus-a", "golden-a"));

    var result = BaselineComparator.compare(baseline, report);

    assertThat(result.baselineValid()).isTrue();
    assertThat(result.oneChunkInvariantHolds()).isTrue();
    assertThat(result.checks()).isNotEmpty();
    assertThat(result.passed()).isTrue();
  }

  @Test
  void failsWhenAMetricDropsBelowTolerance() {
    Baseline baseline = baselineWith(fixedPoints("m1", "d1", "corpus-a", "golden-a"));
    EvaluationReport report =
        reportWith(
            runConfiguration("m1", "d1", "corpus-a", "golden-a"),
            new MetricsAggregate(121, 0.10, 0.10, 0.10, 0.10, 1.0, 94));

    var result = BaselineComparator.compare(baseline, report);

    assertThat(result.passed()).isFalse();
    assertThat(result.failedChecks()).isNotEmpty();
    assertThat(result.failedChecks())
        .allSatisfy(check -> assertThat(check.group()).isEqualTo(Baseline.OVERALL));
  }

  @Test
  void hardFloorIsRelativeToTheCommittedBaselineValue() {
    // ADR-0013 decision 4: the hard floor is 80% of the *committed* baseline value, not a fixed
    // absolute number. A current value just above 80% of baseline passes the hard floor even
    // though it is (deliberately, for this test) far outside the tolerance, so the *tolerance*
    // check is what actually fails it — the hard floor is a separate, looser backstop.
    Baseline baseline = baselineWith(fixedPoints("m1", "d1", "corpus-a", "golden-a"));
    EvaluationReport report =
        reportWith(
            runConfiguration("m1", "d1", "corpus-a", "golden-a"),
            new MetricsAggregate(121, 0.45, 0.45, 0.45, 0.45, 1.0, 94));

    var result = BaselineComparator.compare(baseline, report);

    var hitRateCheck =
        result.checks().stream()
            .filter(c -> c.group().equals(Baseline.OVERALL) && c.metric().equals("hitRateAt5"))
            .findFirst()
            .orElseThrow();
    // 0.8 * 0.521 = 0.4168 — 0.45 clears the hard floor...
    assertThat(hitRateCheck.hardFloor()).isCloseTo(0.8 * 0.521, within(1e-9));
    assertThat(hitRateCheck.passesHardFloor()).isTrue();
    // ...but still fails the (much tighter) baseline-relative tolerance.
    assertThat(hitRateCheck.withinTolerance()).isFalse();
    assertThat(result.passed()).isFalse();
  }

  @Test
  void hardFloorFallsBackToTheFixedAbsoluteValueOnceTheBaselineItselfHasEroded() {
    // ADR-0013 Nachtrag (second PR #301 review round): a purely baseline-relative floor (0.8 *
    // baselineValue) tracks a baseline down if the baseline itself erodes over successive PRs,
    // instead of anchoring against that erosion. With an (artificially low, for this test)
    // committed baseline of 0.30, 0.8 * 0.30 = 0.24 — but the fixed absolute floor (0.30) is
    // higher and must win via max(...), otherwise the floor would have silently loosened together
    // with the eroded baseline.
    Baseline erodedBaseline =
        new Baseline(
            1,
            fixedPoints("m1", "d1", "corpus-a", "golden-a"),
            Map.of(Baseline.OVERALL, new MetricsAggregate(121, 0.30, 0.461, 0.445, 0.490, 1.0, 94)),
            "2026-08-03",
            null,
            "eroded baseline fixture");
    EvaluationReport report =
        reportWith(
            runConfiguration("m1", "d1", "corpus-a", "golden-a"),
            new MetricsAggregate(121, 0.25, 0.461, 0.445, 0.490, 1.0, 94));

    var result = BaselineComparator.compare(erodedBaseline, report);

    var hitRateCheck =
        result.checks().stream()
            .filter(c -> c.group().equals(Baseline.OVERALL) && c.metric().equals("hitRateAt5"))
            .findFirst()
            .orElseThrow();
    assertThat(hitRateCheck.hardFloor()).isCloseTo(0.30, within(1e-9));
    assertThat(hitRateCheck.passesHardFloor()).isFalse();
    assertThat(result.passed()).isFalse();
  }

  @Test
  void failsWhenOneChunkInvariantIsViolated() {
    Baseline baseline = baselineWith(fixedPoints("m1", "d1", "corpus-a", "golden-a"));
    EvaluationReport report =
        reportWith(
            runConfiguration("m1", "d1", "corpus-a", "golden-a"),
            overallMetrics(),
            new OneChunkInvariantResult(
                1458, List.of(new OneChunkInvariantResult.Violation("comic-0999_x.md", 2))));

    var result = BaselineComparator.compare(baseline, report);

    assertThat(result.oneChunkInvariantHolds()).isFalse();
    assertThat(result.passed()).isFalse();
  }

  @Test
  void failsWhenReportIsMissingAGroupThatIsPresentInTheBaseline() {
    // PR #301 review: a report whose byCategory/byDifficulty/byLanguage came back empty or partial
    // must not silently pass with only the four overall checks run.
    Baseline baseline =
        new Baseline(
            1,
            fixedPoints("m1", "d1", "corpus-a", "golden-a"),
            Map.of(
                Baseline.OVERALL,
                overallMetrics(),
                Baseline.category("attribute_lookup"),
                overallMetrics()),
            "2026-08-03",
            null,
            "test fixture");
    EvaluationReport report = reportWith(runConfiguration("m1", "d1", "corpus-a", "golden-a"));

    assertThatMissingGroupThrows(baseline, report);
  }

  private static void assertThatMissingGroupThrows(Baseline baseline, EvaluationReport report) {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> BaselineComparator.compare(baseline, report))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("category:attribute_lookup");
  }

  // --- fixtures -----------------------------------------------------------------------------

  private static MetricsAggregate overallMetrics() {
    return new MetricsAggregate(121, 0.521, 0.461, 0.445, 0.490, 0.9708, 94);
  }

  private static Baseline.FixedPoints fixedPoints(
      String model, String digest, String corpusSha, String goldenSha) {
    return new Baseline.FixedPoints(
        model,
        digest,
        768,
        1000,
        true,
        10,
        0.3,
        "hnsw",
        corpusSha,
        1458,
        "eval/golden/x.json",
        goldenSha,
        121);
  }

  private static RunConfiguration runConfiguration(
      String model, String digest, String corpusSha, String goldenSha) {
    return new RunConfiguration(
        "ollama",
        model,
        digest,
        "ollama/ollama:0.6.5",
        768,
        1000,
        true,
        10,
        0.3,
        "note",
        "hnsw",
        corpusSha,
        1458,
        "eval/golden/x.json",
        goldenSha,
        121,
        "2026-08-03T00:00:00Z",
        1004.0);
  }

  private static Baseline baselineWith(Baseline.FixedPoints fixedPoints) {
    return new Baseline(
        1,
        fixedPoints,
        Map.of(Baseline.OVERALL, overallMetrics()),
        "2026-08-03",
        null,
        "test fixture");
  }

  private static EvaluationReport reportWith(RunConfiguration cfg) {
    return reportWith(cfg, overallMetrics());
  }

  private static EvaluationReport reportWith(RunConfiguration cfg, MetricsAggregate overall) {
    return reportWith(cfg, overall, new OneChunkInvariantResult(1458, List.of()));
  }

  private static EvaluationReport reportWith(
      RunConfiguration cfg, MetricsAggregate overall, OneChunkInvariantResult invariant) {
    return new EvaluationReport(
        1,
        cfg,
        invariant,
        new EvaluationReport.DatasetNotes(121, 94, "note"),
        overall,
        Map.of(),
        Map.of(),
        Map.of(),
        List.of(),
        List.of());
  }
}
