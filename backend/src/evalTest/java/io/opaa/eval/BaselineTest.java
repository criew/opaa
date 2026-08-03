package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Docker-free unit tests for {@link Baseline#load(Path)} — in particular the {@code
 * distinctExpectedDocumentSets} validation added after the second PR #301 review round. Part of
 * {@code evalUnitTest}, wired into {@code check}.
 */
class BaselineTest {

  @TempDir Path tempDir;

  @Test
  void loadsAValidBaselineFile() throws IOException {
    Path file = tempDir.resolve("baseline.json");
    Files.writeString(file, VALID_BASELINE_JSON);

    Baseline baseline = Baseline.load(file);

    assertThat(baseline.groups().get(Baseline.OVERALL).distinctExpectedDocumentSets())
        .isEqualTo(94);
  }

  @Test
  void rejectsAGroupWithMissingDistinctExpectedDocumentSets() throws IOException {
    // Missing the field entirely — Jackson would otherwise default the int to 0, and
    // BaselineComparator would silently treat that as n_eff=1, the loosest possible tolerance.
    Path file = tempDir.resolve("baseline.json");
    Files.writeString(
        file, VALID_BASELINE_JSON.replace("\"distinctExpectedDocumentSets\": 94,", ""));

    assertThatThrownBy(() -> Baseline.load(file))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("overall")
        .hasMessageContaining("distinctExpectedDocumentSets");
  }

  @Test
  void rejectsAGroupWithZeroDistinctExpectedDocumentSets() throws IOException {
    Path file = tempDir.resolve("baseline.json");
    Files.writeString(
        file,
        VALID_BASELINE_JSON.replace(
            "\"distinctExpectedDocumentSets\": 94,", "\"distinctExpectedDocumentSets\": 0,"));

    assertThatThrownBy(() -> Baseline.load(file))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("distinctExpectedDocumentSets=0");
  }

  private static final String VALID_BASELINE_JSON =
      """
      {
        "measurementContractVersion": 1,
        "fixedPoints": {
          "embeddingModel": "nomic-embed-text:v1.5",
          "embeddingModelDigest": "abc",
          "embeddingDimensions": 768,
          "chunkSize": 1000,
          "chunkSizeMatchesApplicationDefault": true,
          "searchTopK": 10,
          "productionSimilarityThreshold": 0.3,
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
            "mrr": 0.461,
            "ndcgAt10": 0.445,
            "recallAt10": 0.490,
            "distinctExpectedDocumentSets": 94,
            "recallAt10Ceiling": 0.9708
          }
        },
        "measuredAt": "2026-08-03",
        "notes": "test fixture"
      }
      """;
}
