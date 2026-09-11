package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards that the multi-turn measurement path (issue #1484) writes where it belongs and is judged
 * against its own baseline - the counterpart of {@link PipelinePathIsolationTest} for the third
 * path. Docker-free by construction: without these assertions a report or baseline file name
 * colliding with the single-question path's would only be noticed by the next expensive run, and
 * only after it had overwritten the other path's numbers.
 */
class ConversationPathIsolationTest {

  /**
   * Pinned, not merely observed: this constant may only move together with every committed
   * conversation baseline's {@code conversationMeasurementContractVersion} and with {@link
   * ConversationBaselineComparator}'s fixed-point list.
   */
  @Test
  void theMultiTurnPathCountsItsOwnContractVersionSeparately() {
    assertThat(ConversationEvaluationReport.CONVERSATION_MEASUREMENT_CONTRACT_VERSION).isEqualTo(1);
  }

  @Test
  void multiTurnReportsAndBaselinesGoToTheirOwnFilePerDomain() {
    EvalDomainConfig domain = EvalDomainConfig.VERWALTUNG;

    assertThat(ConversationHarnessSupport.reportFile(domain))
        .hasFileName("pipeline-conversations-verwaltung.json");
    assertThat(domain.conversationDatasetFileName()).isEqualTo("verwaltung-conversations.json");
    assertThat(domain.conversationBaselineFileName())
        .isEqualTo("pipeline-verwaltung-conversations.json");

    // Per directory, because eval/golden and eval/baseline legitimately share a domain's plain
    // name: a collision only matters between files that land next to each other.
    assertThat(List.of(domain.goldenDatasetFileName(), domain.conversationDatasetFileName()))
        .doesNotHaveDuplicates();
    assertThat(
            List.of(
                domain.baselineFileName(),
                domain.pipelineBaselineFileName(),
                domain.conversationBaselineFileName()))
        .doesNotHaveDuplicates();
    assertThat(
            List.of(
                ConversationHarnessSupport.reportFile(domain).getFileName().toString(),
                ConversationHarnessSupport.markdownFile(domain).getFileName().toString(),
                PipelineHarnessSupport.reportFile(domain).getFileName().toString()))
        .doesNotHaveDuplicates();
  }

  /** The committed multi-turn dataset must at least parse on every build. */
  @Test
  void theCommittedMultiTurnDatasetParses() throws IOException {
    assertThat(ConversationDataset.load(ConversationDataset.file(EvalDomainConfig.VERWALTUNG)))
        .isNotNull();
  }

  /**
   * A baseline drawn without decomposition (or without a chat model) describes a run that could not
   * resolve a single reference - it is refused at load time rather than silently compared against a
   * real run.
   */
  @Test
  void aBaselineWithoutDecompositionIsRefusedAtLoadTime(@TempDir Path tempDir) throws IOException {
    Path file = tempDir.resolve("pipeline-verwaltung-conversations.json");
    Files.writeString(
        file,
        """
        {
          "conversationMeasurementContractVersion": 1,
          "fixedPoints": {
            "pipeline": {
              "embeddingModel": "nomic-embed-text:v1.5",
              "embeddingModelDigest": "digest",
              "embeddingDimensions": 768,
              "chunkSize": 1000,
              "chunkSizeMatchesApplicationDefault": true,
              "chunkOverlap": 200,
              "fetchK": 60,
              "topK": 8,
              "similarityThreshold": 0.3,
              "maxChunksPerDocument": 3,
              "mmrLambda": 0.7,
              "fullTextSearchEnabled": true,
              "fullTextIndexUpToDate": true,
              "queryDecompositionEnabled": false,
              "maxSubQueries": 4,
              "chatModel": null,
              "hitRateK": 5,
              "rankingK": 8,
              "pgvectorIndexType": "hnsw",
              "corpusManifestSha256": "corpus",
              "corpusDocumentCount": 72,
              "goldenDatasetFile": "eval/golden/verwaltung-conversations.json",
              "goldenDatasetSha256": "dataset",
              "goldenCaseCount": 8,
              "ingestionPipelineFingerprint": "markdown:3",
              "metadataFilterEnabled": false
            },
            "conversationWindowMessages": 20,
            "searchWindowTurns": 0,
            "conversationNoteCap": 0,
            "turnCount": 20
          },
          "groups": {},
          "measuredAt": "2026-09-11T00:00:00Z",
          "notes": ""
        }
        """);

    assertThatThrownBy(() -> ConversationBaseline.load(file))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("queryDecompositionEnabled=false");
  }

  /**
   * A changed conversation window, search window or note cap makes the committed baseline
   * incomparable - it did not get worse, it measured something else.
   */
  @Test
  void aChangedConversationMemoryProfileInvalidatesTheBaseline() {
    ConversationBaseline baseline =
        new ConversationBaseline(
            ConversationEvaluationReport.CONVERSATION_MEASUREMENT_CONTRACT_VERSION,
            new ConversationBaseline.FixedPoints(
                pipelineFixedPoints(),
                20,
                ConversationMemoryProfile.SEARCH_WINDOW_WHOLE_CONVERSATION_WINDOW,
                0,
                20),
            Map.of(),
            "2026-09-11T00:00:00Z",
            null,
            "");

    ConversationBaselineComparator.ComparisonResult result =
        ConversationBaselineComparator.compare(baseline, reportWithSearchWindowTurns(2));

    assertThat(result.baselineValid()).isFalse();
    assertThat(result.fixedPointMismatches())
        .extracting(BaselineComparator.FixedPointMismatch::field)
        .contains("searchWindowTurns");
  }

  private static PipelineBaseline.FixedPoints pipelineFixedPoints() {
    return new PipelineBaseline.FixedPoints(
        "nomic-embed-text:v1.5",
        "digest",
        768,
        1000,
        true,
        200,
        60,
        8,
        0.3,
        3,
        0.7,
        true,
        true,
        true,
        4,
        "qwen2.5:1.5b-instruct",
        5,
        8,
        "hnsw",
        "corpus",
        72,
        "eval/golden/verwaltung-conversations.json",
        "dataset",
        8,
        "markdown:3",
        false);
  }

  private static ConversationEvaluationReport reportWithSearchWindowTurns(int searchWindowTurns) {
    return new ConversationEvaluationReport(
        ConversationEvaluationReport.CONVERSATION_MEASUREMENT_CONTRACT_VERSION,
        PipelineMetricsAggregate.METRIC_WINDOW_NOTE,
        ConversationEvaluationReport.SINGLE_PATH_NOTE,
        new ConversationEvaluationReport.ConversationRunConfiguration(
            new PipelineEvaluationReport.PipelineRunConfiguration(
                "verwaltung",
                "ollama",
                "nomic-embed-text:v1.5",
                "digest",
                "ollama/ollama:0.6.5",
                768,
                1000,
                true,
                200,
                60,
                8,
                0.3,
                "note",
                3,
                0.7,
                true,
                true,
                true,
                4,
                "qwen2.5:1.5b-instruct",
                5,
                8,
                "hnsw",
                "corpus",
                72,
                "eval/golden/verwaltung-conversations.json",
                "dataset",
                8,
                "markdown:3",
                false,
                1,
                "scope",
                "2026-09-11T00:00:00Z",
                1.0,
                false),
            new ConversationMemoryProfile(20, searchWindowTurns, 0),
            20),
        PipelineMetricsAggregate.of(List.of()),
        Map.of(),
        Map.of(),
        new ConversationEvaluationReport.CaseOutcomeSummary(0, 0, Map.of()),
        null,
        null,
        List.of());
  }
}
