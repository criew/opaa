package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.opaa.eval.EvaluationReport.OneChunkInvariantResult;
import io.opaa.eval.EvaluationReport.RunConfiguration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Docker-free unit tests for the tolerance formula and fixed-point comparison in {@link
 * BaselineComparator} — see its Javadoc for the rationale. Part of {@code evalUnitTest}, wired into
 * {@code check}.
 */
class BaselineComparatorTest {

  @Test
  void toleranceIsDominatedByRelativeTermForLargeHighScoringGroups() {
    // attribute_lookup-like: n=30, ndcg=0.942 → relative term (0.113) dominates but is capped.
    assertThat(BaselineComparator.toleranceFor(0.942, 30)).isEqualTo(0.05, within(1e-9));
  }

  @Test
  void toleranceIsDominatedByOneCaseGuardForSmallLowScoringGroups() {
    // numeric_range-like: n=16, ndcg=0.063 → relative term (0.00756) and floor (0.02) both lose to
    // the 1/n guard (0.0625), which then gets capped.
    assertThat(BaselineComparator.toleranceFor(0.063, 16)).isEqualTo(0.05, within(1e-9));
  }

  @Test
  void toleranceFallsBackToAbsoluteFloorWhenBothOtherTermsAreTiny() {
    // Large n, very low baseline value: relative term and 1/n term both collapse, floor kicks in.
    assertThat(BaselineComparator.toleranceFor(0.01, 500)).isEqualTo(0.02, within(1e-9));
  }

  @Test
  void toleranceUsesRelativeTermWhenItIsTheMiddleValue() {
    // crosslingual-like: n=34, ndcg=0.302 → relative (0.03624) beats both 1/n (0.0294) and floor
    // (0.02), and stays below the cap.
    assertThat(BaselineComparator.toleranceFor(0.302, 34)).isEqualTo(0.03624, within(1e-9));
  }

  @Test
  void detectsCorpusManifestDrift() {
    Baseline baseline = baselineWith(fixedPoints("m1", "d1", 1000, true, "corpus-a", "golden-a"));
    EvaluationReport report =
        reportWith(runConfiguration("m1", "d1", 1000, true, "corpus-b", "golden-a"));

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
    Baseline baseline = baselineWith(fixedPoints("m1", "d1", 1000, true, "corpus-a", "golden-a"));
    EvaluationReport report =
        reportWith(runConfiguration("m1", "d2", 1000, true, "corpus-a", "golden-a"));

    var result = BaselineComparator.compare(baseline, report);

    assertThat(result.baselineValid()).isFalse();
    assertThat(result.fixedPointMismatches())
        .extracting(BaselineComparator.FixedPointMismatch::field)
        .containsExactly("embeddingModelDigest");
  }

  @Test
  void passesWhenFixedPointsMatchAndMetricsAreWithinTolerance() {
    Baseline baseline = baselineWith(fixedPoints("m1", "d1", 1000, true, "corpus-a", "golden-a"));
    EvaluationReport report =
        reportWith(runConfiguration("m1", "d1", 1000, true, "corpus-a", "golden-a"));

    var result = BaselineComparator.compare(baseline, report);

    assertThat(result.baselineValid()).isTrue();
    assertThat(result.oneChunkInvariantHolds()).isTrue();
    assertThat(result.checks()).isNotEmpty();
    assertThat(result.passed()).isTrue();
  }

  @Test
  void failsWhenAMetricDropsBelowTolerance() {
    Baseline baseline = baselineWith(fixedPoints("m1", "d1", 1000, true, "corpus-a", "golden-a"));
    EvaluationReport report =
        reportWith(
            runConfiguration("m1", "d1", 1000, true, "corpus-a", "golden-a"),
            new MetricsAggregate(121, 0.10, 0.10, 0.10, 0.10, 1.0));

    var result = BaselineComparator.compare(baseline, report);

    assertThat(result.passed()).isFalse();
    assertThat(result.failedChecks()).isNotEmpty();
    assertThat(result.failedChecks())
        .allSatisfy(check -> assertThat(check.group()).isEqualTo(Baseline.OVERALL));
  }

  @Test
  void failsWhenOneChunkInvariantIsViolated() {
    Baseline baseline = baselineWith(fixedPoints("m1", "d1", 1000, true, "corpus-a", "golden-a"));
    EvaluationReport report =
        reportWith(
            runConfiguration("m1", "d1", 1000, true, "corpus-a", "golden-a"),
            overallMetrics(),
            new OneChunkInvariantResult(
                1458, List.of(new OneChunkInvariantResult.Violation("comic-0999_x.md", 2))));

    var result = BaselineComparator.compare(baseline, report);

    assertThat(result.oneChunkInvariantHolds()).isFalse();
    assertThat(result.passed()).isFalse();
  }

  // --- fixtures -----------------------------------------------------------------------------

  private static MetricsAggregate overallMetrics() {
    return new MetricsAggregate(121, 0.521, 0.461, 0.445, 0.490, 0.9708);
  }

  private static Baseline.FixedPoints fixedPoints(
      String model,
      String digest,
      int chunkSize,
      boolean matchesDefault,
      String corpusSha,
      String goldenSha) {
    return new Baseline.FixedPoints(
        model,
        digest,
        chunkSize,
        matchesDefault,
        corpusSha,
        1458,
        "eval/golden/x.json",
        goldenSha,
        121);
  }

  private static RunConfiguration runConfiguration(
      String model,
      String digest,
      int chunkSize,
      boolean matchesDefault,
      String corpusSha,
      String goldenSha) {
    return new RunConfiguration(
        "ollama",
        model,
        digest,
        "ollama/ollama:0.6.5",
        768,
        chunkSize,
        matchesDefault,
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
        1, fixedPoints, Map.of(Baseline.OVERALL, overallMetrics()), "2026-08-03", "test fixture");
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
        new EvaluationReport.DatasetNotes(121, 75, "note"),
        overall,
        Map.of(),
        Map.of(),
        Map.of(),
        List.of(),
        List.of());
  }
}
