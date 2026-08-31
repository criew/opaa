package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Docker-free unit tests for the pipeline path's baseline comparison (issue #1040): that the five
 * previously unchecked query parameters now invalidate a baseline, that a pipeline comparison
 * cannot be run against a raw-vector baseline, and that the error criterion applied is literally
 * ADR-0013's.
 */
class PipelineBaselineComparatorTest {

  private static final double DELTA = 1e-9;

  private static PipelineMetricsAggregate aggregate(double value, int n) {
    return new PipelineMetricsAggregate(n, value, value, value, value, 1.0, n, n, n, value);
  }

  private static Map<String, PipelineMetricsAggregate> groups(double value, int n) {
    return Map.of(
        Baseline.OVERALL,
        aggregate(value, n),
        Baseline.category("cat"),
        aggregate(value, n),
        Baseline.difficulty("easy"),
        aggregate(value, n),
        Baseline.language("de"),
        aggregate(value, n));
  }

  private static PipelineBaseline.FixedPoints fixedPoints() {
    return new PipelineBaseline.FixedPoints(
        "nomic-embed-text:v1.5",
        "digest",
        768,
        1000,
        true,
        100,
        25,
        8,
        0.3,
        2,
        1.0,
        false,
        3,
        null,
        5,
        8,
        "hnsw",
        "manifest",
        3,
        "eval/golden/test.json",
        "golden",
        20);
  }

  private static PipelineBaseline baseline(double value, int n) {
    return new PipelineBaseline(
        PipelineEvaluationReport.PIPELINE_MEASUREMENT_CONTRACT_VERSION,
        fixedPoints(),
        groups(value, n),
        "2026-08-31",
        null,
        "test");
  }

  private static PipelineEvaluationReport report(
      double value, int n, PipelineEvaluationReport.PipelineRunConfiguration configuration) {
    return new PipelineEvaluationReport(
        PipelineEvaluationReport.PIPELINE_MEASUREMENT_CONTRACT_VERSION,
        PipelineMetricsAggregate.METRIC_WINDOW_NOTE,
        configuration,
        new PipelineEvaluationReport.SelectionCoverage(n, 0, 1, 8, 4.0, 3.0),
        aggregate(value, n),
        Map.of("cat", aggregate(value, n)),
        Map.of("easy", aggregate(value, n)),
        Map.of("de", aggregate(value, n)),
        List.of(),
        List.of());
  }

  private static PipelineEvaluationReport.PipelineRunConfiguration runConfiguration(
      double mmrLambda, int fetchK, double similarityThreshold, int maxSubQueries) {
    return new PipelineEvaluationReport.PipelineRunConfiguration(
        "test-domain",
        "ollama",
        "nomic-embed-text:v1.5",
        "digest",
        "ollama/ollama:0.6.5",
        768,
        1000,
        true,
        100,
        fetchK,
        8,
        similarityThreshold,
        "angewandt",
        2,
        mmrLambda,
        false,
        maxSubQueries,
        null,
        5,
        8,
        "hnsw",
        "manifest",
        3,
        "eval/golden/test.json",
        "golden",
        20,
        1,
        PipelineHarnessSupport.SEARCH_SCOPE_NOTE,
        "2026-08-31T00:00:00Z",
        1.0,
        false);
  }

  private static PipelineEvaluationReport.PipelineRunConfiguration externalEndpointRunConfiguration(
      PipelineEvaluationReport.PipelineRunConfiguration cfg) {
    return new PipelineEvaluationReport.PipelineRunConfiguration(
        cfg.domain(),
        cfg.embeddingProvider(),
        cfg.embeddingModel(),
        cfg.embeddingModelDigest(),
        cfg.ollamaImage(),
        cfg.embeddingDimensions(),
        cfg.chunkSize(),
        cfg.chunkSizeMatchesApplicationDefault(),
        cfg.chunkOverlap(),
        cfg.fetchK(),
        cfg.topK(),
        cfg.similarityThreshold(),
        cfg.similarityThresholdNote(),
        cfg.maxChunksPerDocument(),
        cfg.mmrLambda(),
        cfg.queryDecompositionEnabled(),
        cfg.maxSubQueries(),
        cfg.chatModel(),
        cfg.hitRateK(),
        cfg.rankingK(),
        cfg.pgvectorIndexType(),
        cfg.corpusManifestSha256(),
        cfg.corpusDocumentCount(),
        cfg.goldenDatasetFile(),
        cfg.goldenDatasetSha256(),
        cfg.goldenCaseCount(),
        cfg.searchScopeLibraryCount(),
        cfg.searchScopeNote(),
        cfg.runStartedAt(),
        cfg.runDurationSeconds(),
        true);
  }

  /**
   * Issue #1076: a run against an external Ollama endpoint may have embedded on a GPU, whose
   * kernels are not guaranteed bit-identical to the CPU Testcontainer the baselines were drawn on —
   * the pipeline path needs the same hard stop the raw-vector path has, not a silent comparison.
   */
  @Test
  void refusesToCompareAReportFromAnExternalOllamaEndpoint() {
    PipelineEvaluationReport report =
        report(0.5, 20, externalEndpointRunConfiguration(matchingRunConfiguration()));

    assertThatThrownBy(() -> PipelineBaselineComparator.requireBaselineComparable(report))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("opaa.eval.ollamaBaseUrl");
  }

  @Test
  void allowsAComparisonOfATestcontainerRun() {
    PipelineEvaluationReport report = report(0.5, 20, matchingRunConfiguration());

    assertThatCode(() -> PipelineBaselineComparator.requireBaselineComparable(report))
        .doesNotThrowAnyException();
  }

  private static PipelineEvaluationReport.PipelineRunConfiguration matchingRunConfiguration() {
    return runConfiguration(1.0, 25, 0.3, 3);
  }

  @Test
  void anUnchangedRunPassesAgainstItsOwnBaseline() {
    PipelineBaselineComparator.ComparisonResult result =
        PipelineBaselineComparator.compare(
            baseline(0.5, 20), report(0.5, 20, matchingRunConfiguration()));

    assertThat(result.baselineValid()).isTrue();
    assertThat(result.passed()).isTrue();
    assertThat(result.checks()).isNotEmpty();
  }

  /**
   * The point of issue #1040's contract-version bump: {@code mmr-lambda} used to be reported but
   * unchecked, so a changed selection heuristic could silently redefine what the committed numbers
   * describe. Same for the other four parameters below.
   */
  @Test
  void aChangedMmrLambdaInvalidatesTheBaselineInsteadOfBeingComparedAgainstIt() {
    PipelineBaselineComparator.ComparisonResult result =
        PipelineBaselineComparator.compare(
            baseline(0.5, 20), report(0.5, 20, runConfiguration(0.7, 25, 0.3, 3)));

    assertThat(result.baselineValid()).isFalse();
    assertThat(result.fixedPointMismatches())
        .extracting(BaselineComparator.FixedPointMismatch::field)
        .containsExactly("mmrLambda");
    assertThat(result.checks()).isEmpty();
  }

  @Test
  void changedFetchKThresholdOrMaxSubQueriesInvalidateTheBaseline() {
    assertThat(
            PipelineBaselineComparator.compare(
                    baseline(0.5, 20), report(0.5, 20, runConfiguration(1.0, 40, 0.3, 3)))
                .fixedPointMismatches())
        .extracting(BaselineComparator.FixedPointMismatch::field)
        .containsExactly("fetchK");
    assertThat(
            PipelineBaselineComparator.compare(
                    baseline(0.5, 20), report(0.5, 20, runConfiguration(1.0, 25, 0.5, 3)))
                .fixedPointMismatches())
        .extracting(BaselineComparator.FixedPointMismatch::field)
        .containsExactly("similarityThreshold");
    assertThat(
            PipelineBaselineComparator.compare(
                    baseline(0.5, 20), report(0.5, 20, runConfiguration(1.0, 25, 0.3, 5)))
                .fixedPointMismatches())
        .extracting(BaselineComparator.FixedPointMismatch::field)
        .containsExactly("maxSubQueries");
  }

  @Test
  void aDifferentPipelineContractVersionInvalidatesTheBaseline() {
    PipelineBaseline stale =
        new PipelineBaseline(
            PipelineEvaluationReport.PIPELINE_MEASUREMENT_CONTRACT_VERSION - 1,
            fixedPoints(),
            groups(0.5, 20),
            "2026-08-31",
            null,
            "test");

    PipelineBaselineComparator.ComparisonResult result =
        PipelineBaselineComparator.compare(stale, report(0.5, 20, matchingRunConfiguration()));

    assertThat(result.baselineValid()).isFalse();
    assertThat(result.fixedPointMismatches())
        .extracting(BaselineComparator.FixedPointMismatch::field)
        .containsExactly("pipelineMeasurementContractVersion");
  }

  /** ADR-0013's tolerance formula, applied to this path's numbers without redefinition. */
  @Test
  void appliesTheUnchangedAdr0013Tolerance() {
    PipelineBaselineComparator.ComparisonResult result =
        PipelineBaselineComparator.compare(
            baseline(0.5, 20), report(0.5, 20, matchingRunConfiguration()));

    double expected = BaselineComparator.toleranceFor(0.5, 20);
    assertThat(result.checks())
        .allSatisfy(check -> assertThat(check.tolerance()).isCloseTo(expected, within(DELTA)));
  }

  @Test
  void aDropBeyondToleranceIsARegression() {
    PipelineBaselineComparator.ComparisonResult result =
        PipelineBaselineComparator.compare(
            baseline(0.5, 20), report(0.2, 20, matchingRunConfiguration()));

    assertThat(result.baselineValid()).isTrue();
    assertThat(result.passed()).isFalse();
    assertThat(result.failedChecks()).isNotEmpty();
  }

  /** Only the overall group carries a hard floor, exactly as on the raw-vector path. */
  @Test
  void onlyTheOverallGroupCarriesAHardFloor() {
    PipelineBaselineComparator.ComparisonResult result =
        PipelineBaselineComparator.compare(
            baseline(0.5, 20), report(0.5, 20, matchingRunConfiguration()));

    assertThat(result.checks())
        .filteredOn(check -> !Baseline.OVERALL.equals(check.group()))
        .allSatisfy(check -> assertThat(check.hardFloor()).isEqualTo(Double.NEGATIVE_INFINITY));
    assertThat(result.checks())
        .filteredOn(
            check -> Baseline.OVERALL.equals(check.group()) && "ndcgAt8".equals(check.metric()))
        .singleElement()
        .satisfies(check -> assertThat(check.hardFloor()).isGreaterThan(0.0));
  }

  @Test
  void aGroupTheBaselineDoesNotKnowIsAHardFailureNotAToleranceCase() {
    PipelineBaseline withoutCategory =
        new PipelineBaseline(
            PipelineEvaluationReport.PIPELINE_MEASUREMENT_CONTRACT_VERSION,
            fixedPoints(),
            Map.of(
                Baseline.OVERALL,
                aggregate(0.5, 20),
                Baseline.difficulty("easy"),
                aggregate(0.5, 20),
                Baseline.language("de"),
                aggregate(0.5, 20)),
            "2026-08-31",
            null,
            "test");

    assertThatThrownBy(
            () ->
                PipelineBaselineComparator.compare(
                    withoutCategory, report(0.5, 20, matchingRunConfiguration())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("category:cat");
  }
}
