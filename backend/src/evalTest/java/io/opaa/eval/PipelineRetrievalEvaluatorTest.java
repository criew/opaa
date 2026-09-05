package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Docker-free unit tests for the pipeline path's case evaluation and report assembly (issue #1039),
 * run via the {@code evalUnitTest} Gradle task. The pipeline itself is a plain function here, which
 * is the whole point of {@link PipelineRetrievalEvaluator} taking one.
 */
class PipelineRetrievalEvaluatorTest {

  private static final double TOLERANCE = 1e-4;

  private static GoldenCase goldenCase(String id, List<String> expected) {
    return new GoldenCase(
        id,
        "test",
        "frage " + id,
        expected,
        "cat",
        "easy",
        "de",
        "t",
        null,
        null,
        null,
        null,
        null);
  }

  private static PipelineEvaluationReport.PipelineRunConfiguration runConfiguration() {
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
        25,
        8,
        0.3,
        "angewandt",
        2,
        1.0,
        true,
        true,
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
        1,
        "markdown:1",
        true,
        1,
        PipelineHarnessSupport.SEARCH_SCOPE_NOTE,
        "2026-08-31T00:00:00Z",
        1.0,
        false);
  }

  /**
   * A document's rank is the rank of its best-placed chunk — several chunks of one document must
   * not each occupy a slot in the document-level ranking, exactly as on the raw-vector path.
   */
  @Test
  void deduplicatesChunksToDocumentsKeepingTheBestRank() {
    var outcome =
        PipelineRetrievalEvaluator.evaluateCase(
            goldenCase("a", List.of("b.md")),
            List.of("a.md", "a.md", "b.md", "b.md"),
            List.of("frage a"));

    assertThat(outcome.chunksReturned()).isEqualTo(4);
    assertThat(outcome.distinctDocumentsReturned()).isEqualTo(2);
    assertThat(outcome.metrics().rankedFileNames()).containsExactly("a.md", "b.md");
    assertThat(outcome.metrics().reciprocalRank()).isCloseTo(0.5, within(TOLERANCE));
  }

  /**
   * With the similarity threshold applied, the pipeline legitimately returns nothing for a query —
   * that must score zero and be counted, not blow up the run.
   */
  @Test
  void anEmptySelectionIsAMeasurableOutcome() {
    var outcome =
        PipelineRetrievalEvaluator.evaluateCase(
            goldenCase("a", List.of("b.md")), List.of(), List.of("frage a"));

    assertThat(outcome.chunksReturned()).isZero();
    assertThat(outcome.distinctDocumentsReturned()).isZero();
    assertThat(outcome.metrics().ndcg()).isZero();
  }

  @Test
  void chunksWithoutFileNameMetadataAreDropped() {
    var outcome =
        PipelineRetrievalEvaluator.evaluateCase(
            goldenCase("a", List.of("b.md")),
            java.util.Arrays.asList(null, "b.md"),
            List.of("frage a"));

    assertThat(outcome.metrics().rankedFileNames()).containsExactly("b.md");
    assertThat(outcome.metrics().reciprocalRank()).isEqualTo(1.0);
  }

  @Test
  void runEvaluatesEveryCaseThroughTheSuppliedPipeline() {
    Map<String, List<String>> pipeline =
        Map.of(
            "frage a", List.of("a.md"),
            "frage b", List.of("x.md", "b.md"));

    PipelineEvaluationReport report =
        PipelineRetrievalEvaluator.report(
            PipelineRetrievalEvaluator.evaluateAll(
                List.of(goldenCase("a", List.of("a.md")), goldenCase("b", List.of("b.md"))),
                query ->
                    new PipelineRetrievalEvaluator.PipelineInvocationResult(
                        pipeline.get(query), List.of(query))),
            runConfiguration());

    assertThat(report.pipelineMeasurementContractVersion())
        .isEqualTo(PipelineEvaluationReport.PIPELINE_MEASUREMENT_CONTRACT_VERSION);
    assertThat(report.metricWindowNote()).contains("nDCG@8").contains("Rohvektor-Pfad");
    assertThat(report.overall().n()).isEqualTo(2);
    assertThat(report.overall().hitRateAt5()).isEqualTo(1.0);
    assertThat(report.overall().mrrAt8()).isCloseTo(0.75, within(TOLERANCE));
    assertThat(report.allQueryResults()).hasSize(2);
    // Worst first: case "b" (hit at rank 2) is ordered before case "a" (hit at rank 1).
    assertThat(report.allQueryResults().get(0).id()).isEqualTo("b");
  }

  @Test
  void selectionCoverageCountsQueriesThatReturnedNothing() {
    var withChunks =
        PipelineRetrievalEvaluator.evaluateCase(
            goldenCase("a", List.of("a.md")), List.of("a.md"), List.of("frage a"));
    var empty =
        PipelineRetrievalEvaluator.evaluateCase(
            goldenCase("b", List.of("b.md")), List.of(), List.of("frage b"));

    var coverage = PipelineRetrievalEvaluator.selectionCoverage(List.of(withChunks, empty));

    assertThat(coverage.queriesEvaluated()).isEqualTo(2);
    assertThat(coverage.queriesWithNoChunks()).isEqualTo(1);
    assertThat(coverage.minChunksReturned()).isZero();
    assertThat(coverage.maxChunksReturned()).isEqualTo(1);
    assertThat(coverage.meanChunksReturned()).isCloseTo(0.5, within(TOLERANCE));
  }
}
