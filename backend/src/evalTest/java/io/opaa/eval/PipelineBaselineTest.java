package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Docker-free unit tests for {@link PipelineBaseline#load(Path)} (issue #1040) — the same load-time
 * guards {@link BaselineTest} covers for the raw-vector path, at this path's windows. Part of
 * {@code evalUnitTest}, wired into {@code check}.
 */
class PipelineBaselineTest {

  @TempDir Path tempDir;

  @Test
  void loadsAValidPipelineBaselineFile() throws IOException {
    Path file = tempDir.resolve("pipeline-baseline.json");
    Files.writeString(file, VALID_JSON);

    PipelineBaseline baseline = PipelineBaseline.load(file);

    assertThat(baseline.fixedPoints().mmrLambda()).isEqualTo(1.0);
    assertThat(baseline.fixedPoints().chatModel()).isNull();
    assertThat(baseline.groups().get(Baseline.OVERALL).distinctExpectedDocumentSets())
        .isEqualTo(94);
  }

  @Test
  void rejectsAGroupWithZeroDistinctExpectedDocumentSets() throws IOException {
    Path file = tempDir.resolve("pipeline-baseline.json");
    Files.writeString(
        file,
        VALID_JSON.replace(
            "\"distinctExpectedDocumentSets\": 94,", "\"distinctExpectedDocumentSets\": 0,"));

    assertThatThrownBy(() -> PipelineBaseline.load(file))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("distinctExpectedDocumentSets=0");
  }

  @Test
  void rejectsAGroupWhereHitCountAt5DoesNotExactlyMatchHitRateAt5TimesN() throws IOException {
    Path file = tempDir.resolve("pipeline-baseline.json");
    Files.writeString(file, VALID_JSON.replace("\"hitCountAt5\": 63,", "\"hitCountAt5\": 0,"));

    assertThatThrownBy(() -> PipelineBaseline.load(file))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("hitCountAt5=0")
        .hasMessageContaining("implies exactly 63");
  }

  @Test
  void rejectsAGroupWhereHitCountAt8IsBelowHitCountAt5() throws IOException {
    Path file = tempDir.resolve("pipeline-baseline.json");
    Files.writeString(file, VALID_JSON.replace("\"hitCountAt8\": 70,", "\"hitCountAt8\": 0,"));

    assertThatThrownBy(() -> PipelineBaseline.load(file))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("0 <= hitCountAt5 <= hitCountAt8 <= n");
  }

  @Test
  void rejectsAGroupWhereAllExpectedDocumentsHitExceedsRecall() throws IOException {
    Path file = tempDir.resolve("pipeline-baseline.json");
    Files.writeString(
        file,
        VALID_JSON.replace(
            "\"allExpectedDocumentsHitAt8\": 0.400", "\"allExpectedDocumentsHitAt8\": 0.900"));

    assertThatThrownBy(() -> PipelineBaseline.load(file))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("allExpectedDocumentsHitAt8");
  }

  private static final String VALID_JSON =
      """
      {
        "pipelineMeasurementContractVersion": 2,
        "fixedPoints": {
          "embeddingModel": "nomic-embed-text:v1.5",
          "embeddingModelDigest": "abc",
          "embeddingDimensions": 768,
          "chunkSize": 1000,
          "chunkSizeMatchesApplicationDefault": true,
          "chunkOverlap": 100,
          "fetchK": 25,
          "topK": 8,
          "similarityThreshold": 0.3,
          "maxChunksPerDocument": 2,
          "mmrLambda": 1.0,
          "queryDecompositionEnabled": false,
          "maxSubQueries": 3,
          "chatModel": null,
          "hitRateK": 5,
          "rankingK": 8,
          "pgvectorIndexType": "hnsw",
          "corpusManifestSha256": "def",
          "corpusDocumentCount": 1448,
          "goldenDatasetFile": "eval/golden/comic-characters.json",
          "goldenDatasetSha256": "ghi",
          "goldenCaseCount": 121
        },
        "groups": {
          "overall": {
            "n": 121,
            "hitRateAt5": 0.521,
            "mrrAt8": 0.461,
            "ndcgAt8": 0.445,
            "recallAt8": 0.490,
            "recallAt8Ceiling": 0.960,
            "distinctExpectedDocumentSets": 94,
            "hitCountAt5": 63,
            "hitCountAt8": 70,
            "allExpectedDocumentsHitAt8": 0.400
          }
        },
        "measuredAt": "2026-08-31",
        "notes": "test fixture"
      }
      """;
}
