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

  // --- issue #306: case-count check for pairs where the mean tolerance is tighter than 1/n -----

  @Test
  void usesCaseBasedCheckIdentifiesExactlyTheSixKnownAffectedPairs() {
    // Nachgerechnet direkt aus der committeten Baseline (eval/baseline/comic-characters.json,
    // Stand Issue #306) — siehe eval/baseline/README.md, Abschnitt "Bekannter ... Grenzfall", und
    // BaselineComparator's class Javadoc for the same table. Recomputing this from the live
    // toleranceFor(...)/usesCaseBasedCheck(...) formulas (rather than hardcoding "affected" as a
    // boolean per case) is itself the point of issue #306's chosen design: this test would fail
    // loudly if a future baseline edit silently changed which pairs are protected.
    assertThat(
            BaselineComparator.usesCaseBasedCheck(BaselineComparator.toleranceFor(0.060, 15), 16))
        .as("numeric_range/recallAt10")
        .isTrue();
    assertThat(
            BaselineComparator.usesCaseBasedCheck(BaselineComparator.toleranceFor(0.063, 15), 16))
        .as("numeric_range/ndcgAt10")
        .isTrue();
    assertThat(
            BaselineComparator.usesCaseBasedCheck(BaselineComparator.toleranceFor(0.101, 15), 16))
        .as("numeric_range/mrr")
        .isTrue();
    assertThat(
            BaselineComparator.usesCaseBasedCheck(BaselineComparator.toleranceFor(0.188, 15), 16))
        .as("numeric_range/hitRateAt5")
        .isTrue();
    assertThat(
            BaselineComparator.usesCaseBasedCheck(BaselineComparator.toleranceFor(0.137, 21), 21))
        .as("multi_attribute_filter/ndcgAt10")
        .isTrue();
    assertThat(
            BaselineComparator.usesCaseBasedCheck(BaselineComparator.toleranceFor(0.159, 21), 21))
        .as("multi_attribute_filter/recallAt10")
        .isTrue();

    // Not affected — the mean tolerance already covers a single-case shift (1/n) for these:
    assertThat(
            BaselineComparator.usesCaseBasedCheck(BaselineComparator.toleranceFor(0.238, 21), 21))
        .as("multi_attribute_filter/hitRateAt5")
        .isFalse();
    assertThat(
            BaselineComparator.usesCaseBasedCheck(BaselineComparator.toleranceFor(0.206, 21), 21))
        .as("multi_attribute_filter/mrr")
        .isFalse();
    assertThat(
            BaselineComparator.usesCaseBasedCheck(BaselineComparator.toleranceFor(0.521, 94), 121))
        .as("overall/hitRateAt5")
        .isFalse();
  }

  @Test
  void oneCaseFlipInNumericRangeNoLongerFalselyFailsTheCaseBasedPairs() {
    // Reproduces the exact scenario issue #306 was filed for: for numeric_range's ndcgAt10, it is
    // enough for a *single* one of the 16 cases to drop from rank 1 (ndcg=1.0) to rank 3
    // (ndcg=1/log2(4)=0.5) — no lost hit, the case still scores > 0 — to swing the group mean by
    // 0.5/16=0.03125, more than twice the old mean tolerance (0.01575 = 0.25 * 0.063). This test
    // proves two things at once: (1) the *old* mean-only tolerance would indeed have failed here
    // (asserted directly below via toleranceFor, the exact pre-#306 formula, unchanged by this
    // fix), and (2) BaselineComparator.compare(...) — using the new case-count check — passes,
    // because the case-count check correctly reads "no hit was actually lost" from
    // hitCountAt10 staying at 4.
    MetricsAggregate baselineNumericRange =
        new MetricsAggregate(16, 0.188, 0.101, 0.063, 0.060, 0.9382, 15, 3, 4);
    // Simulates exactly one case's ndcg dropping from 1.0 to 0.5 while remaining a hit
    // (hitCountAt10
    // unchanged at 4) — the other three metrics are left at their baseline value for this test's
    // focus on ndcgAt10, matching how a rank-only shift on a single case would only move ndcgAt10.
    MetricsAggregate currentNumericRange =
        new MetricsAggregate(16, 0.188, 0.101, 0.063 - 0.5 / 16, 0.060, 0.9382, 15, 3, 4);

    double oldTolerance = BaselineComparator.toleranceFor(0.063, 15);
    double actualDelta = currentNumericRange.ndcgAt10() - baselineNumericRange.ndcgAt10();
    assertThat(-actualDelta)
        .as("the swing a single rank-only flip causes must exceed the old mean tolerance")
        .isGreaterThan(oldTolerance);

    Baseline baseline =
        new Baseline(
            1,
            fixedPoints("m1", "d1", "corpus-a", "golden-a"),
            Map.of(
                Baseline.OVERALL,
                overallMetrics(),
                Baseline.category("numeric_range"),
                baselineNumericRange),
            "2026-08-03",
            null,
            "test fixture — issue #306");
    EvaluationReport report =
        new EvaluationReport(
            1,
            runConfiguration("m1", "d1", "corpus-a", "golden-a"),
            new OneChunkInvariantResult(1458, List.of()),
            new EvaluationReport.DatasetNotes(121, 94, "note"),
            overallMetrics(),
            Map.of("numeric_range", currentNumericRange),
            Map.of(),
            Map.of(),
            List.of(),
            List.of());

    var result = BaselineComparator.compare(baseline, report);

    var ndcgCheck =
        result.checks().stream()
            .filter(
                c ->
                    c.group().equals(Baseline.category("numeric_range"))
                        && c.metric().equals("ndcgAt10"))
            .findFirst()
            .orElseThrow();
    assertThat(ndcgCheck.caseBasedCheck()).isTrue();
    assertThat(ndcgCheck.withinTolerance())
        .as("a rank-only flip with no lost hit must not be flagged as a regression")
        .isTrue();
    assertThat(result.passed()).isTrue();
  }

  @Test
  void multipleLostHitsInNumericRangeStillFailTheCaseBasedCheck() {
    // A real regression — more than MAX_CASE_COUNT_DROP (1) cases losing their only hit — must
    // still be caught, proving the fix for issue #306 does not simply loosen the check.
    MetricsAggregate baselineNumericRange =
        new MetricsAggregate(16, 0.188, 0.101, 0.063, 0.060, 0.9382, 15, 3, 4);
    // Two of the four previously-hitting cases now miss entirely (hitCountAt10 drops from 4 to 2).
    MetricsAggregate currentNumericRange =
        new MetricsAggregate(16, 0.188, 0.101, 0.063 - 2.0 / 16, 0.060, 0.9382, 15, 3, 2);

    Baseline baseline =
        new Baseline(
            1,
            fixedPoints("m1", "d1", "corpus-a", "golden-a"),
            Map.of(
                Baseline.OVERALL,
                overallMetrics(),
                Baseline.category("numeric_range"),
                baselineNumericRange),
            "2026-08-03",
            null,
            "test fixture — issue #306");
    EvaluationReport report =
        new EvaluationReport(
            1,
            runConfiguration("m1", "d1", "corpus-a", "golden-a"),
            new OneChunkInvariantResult(1458, List.of()),
            new EvaluationReport.DatasetNotes(121, 94, "note"),
            overallMetrics(),
            Map.of("numeric_range", currentNumericRange),
            Map.of(),
            Map.of(),
            List.of(),
            List.of());

    var result = BaselineComparator.compare(baseline, report);

    var ndcgCheck =
        result.checks().stream()
            .filter(
                c ->
                    c.group().equals(Baseline.category("numeric_range"))
                        && c.metric().equals("ndcgAt10"))
            .findFirst()
            .orElseThrow();
    assertThat(ndcgCheck.caseBasedCheck()).isTrue();
    assertThat(ndcgCheck.withinTolerance())
        .as("losing two of four hits must still be flagged as a regression")
        .isFalse();
    assertThat(result.passed()).isFalse();
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
            cfg.chunkOverlap(),
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
            new MetricsAggregate(121, 0.10, 0.10, 0.10, 0.10, 1.0, 94, 12, 12));

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
            new MetricsAggregate(121, 0.45, 0.45, 0.45, 0.45, 1.0, 94, 54, 54));

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
            Map.of(
                Baseline.OVERALL,
                new MetricsAggregate(121, 0.30, 0.461, 0.445, 0.490, 1.0, 94, 36, 73)),
            "2026-08-03",
            null,
            "eroded baseline fixture");
    EvaluationReport report =
        reportWith(
            runConfiguration("m1", "d1", "corpus-a", "golden-a"),
            new MetricsAggregate(121, 0.25, 0.461, 0.445, 0.490, 1.0, 94, 30, 73));

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
  void ignoresTheRedundantLanguageDeGroupEvenThoughTheReportStillProducesIt() {
    // Issue #304: category:crosslingual and language:de are, by construction, exactly the same
    // case set in the golden dataset. The baseline no longer carries a language:de entry, but the
    // harness still computes a language:de aggregate from the (unchanged) golden dataset. Without
    // the skip in BaselineComparator.compare, this would throw "Baseline has no entry for group
    // 'language:de'" via checkGroup — proving the fix requires the report to still contain the
    // group despite the baseline dropping it.
    //
    // PR #673 review: the fixture also carries a second language group (language:en) with its own
    // baseline entry, to prove the skip is specific to language:de and does not accidentally
    // swallow every language group.
    Baseline baseline =
        new Baseline(
            1,
            fixedPoints("m1", "d1", "corpus-a", "golden-a"),
            Map.of(
                Baseline.OVERALL,
                overallMetrics(),
                Baseline.category("crosslingual"),
                overallMetrics(),
                Baseline.language("en"),
                overallMetrics()),
            "2026-08-03",
            null,
            "test fixture — language:de deliberately absent (issue #304)");
    RunConfiguration cfg = runConfiguration("m1", "d1", "corpus-a", "golden-a");
    EvaluationReport report =
        new EvaluationReport(
            1,
            cfg,
            new OneChunkInvariantResult(1458, List.of()),
            new EvaluationReport.DatasetNotes(121, 94, "note"),
            overallMetrics(),
            Map.of("crosslingual", overallMetrics()),
            Map.of(),
            Map.of("de", overallMetrics(), "en", overallMetrics()),
            List.of(),
            List.of());

    var result = BaselineComparator.compare(baseline, report);

    assertThat(result.baselineValid()).isTrue();
    assertThat(result.checks())
        .extracting(BaselineComparator.MetricCheck::group)
        .doesNotContain("language:de")
        .contains("language:en");
    assertThat(result.passed()).isTrue();
  }

  @Test
  void comparesLanguageDeNormallyOnceTheBaselineCarriesItAgain() {
    // PR #673 review: the skip in BaselineComparator.compare must be self-healing — it only fires
    // while the baseline genuinely lacks a language:de entry. Should a future baseline
    // re-measurement legitimately reintroduce the group (e.g. the golden dataset gains German
    // cases that are not simply crosslingual's twins), it must be compared like any other group,
    // not silently discarded.
    Baseline baseline =
        new Baseline(
            1,
            fixedPoints("m1", "d1", "corpus-a", "golden-a"),
            Map.of(Baseline.OVERALL, overallMetrics(), Baseline.language("de"), overallMetrics()),
            "2026-08-03",
            null,
            "test fixture — language:de present again");
    RunConfiguration cfg = runConfiguration("m1", "d1", "corpus-a", "golden-a");
    EvaluationReport report =
        new EvaluationReport(
            1,
            cfg,
            new OneChunkInvariantResult(1458, List.of()),
            new EvaluationReport.DatasetNotes(121, 94, "note"),
            overallMetrics(),
            Map.of(),
            Map.of(),
            Map.of("de", overallMetrics()),
            List.of(),
            List.of());

    var result = BaselineComparator.compare(baseline, report);

    assertThat(result.baselineValid()).isTrue();
    assertThat(result.checks())
        .extracting(BaselineComparator.MetricCheck::group)
        .contains("language:de");
    assertThat(result.passed()).isTrue();
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
    return new MetricsAggregate(121, 0.521, 0.461, 0.445, 0.490, 0.9708, 94, 63, 73);
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
        0,
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
