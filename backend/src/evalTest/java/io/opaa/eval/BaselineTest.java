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
    // Missing the field entirely. Jackson 2 defaulted the absent int to 0, which is what
    // Baseline.validate() was written to catch; Jackson 3 (migrated in e15f6ef) refuses the mapping
    // outright, because FAIL_ON_NULL_FOR_PRIMITIVES is on by default. The guard therefore bites one
    // layer earlier than originally designed for this particular case — validate() still covers an
    // explicit 0 or a negative value, which deserialize without complaint. What matters either way
    // is that load() never hands back a baseline whose tolerance denominator is silently 0, so this
    // asserts the outcome rather than the exception type.
    Path file = tempDir.resolve("baseline.json");
    Files.writeString(
        file, VALID_BASELINE_JSON.replace("\"distinctExpectedDocumentSets\": 94,", ""));

    assertThatThrownBy(() -> Baseline.load(file))
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

  @Test
  void rejectsAGroupWhereHitCountAt5DoesNotExactlyMatchHitRateAt5TimesN() throws IOException {
    // Issue #306 review, Befund 3: hitRateAt5 is binary per case, so hitCountAt5 ==
    // round(hitRateAt5
    // * n) is an *exact* invariant, not a heuristic — this catches a wrong-but-still-positive count
    // (e.g. a typo), not just a missing/zero one. round(0.521 * 121) = 63, not 0.
    Path file = tempDir.resolve("baseline.json");
    Files.writeString(
        file, VALID_BASELINE_JSON.replace("\"hitCountAt5\": 63,", "\"hitCountAt5\": 0,"));

    assertThatThrownBy(() -> Baseline.load(file))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("hitCountAt5=0")
        .hasMessageContaining("implies exactly 63");
  }

  @Test
  void rejectsAGroupWhereHitCountAt10IsBelowHitCountAt5() throws IOException {
    // 0 <= hitCountAt5 <= hitCountAt10 <= n: every top-5 hit is also a top-10 hit, so hitCountAt10
    // can never be lower than hitCountAt5.
    Path file = tempDir.resolve("baseline.json");
    Files.writeString(
        file, VALID_BASELINE_JSON.replace("\"hitCountAt10\": 73", "\"hitCountAt10\": 0"));

    assertThatThrownBy(() -> Baseline.load(file))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("hitCountAt10=0")
        .hasMessageContaining("0 <= hitCountAt5 <= hitCountAt10 <= n");
  }

  @Test
  void rejectsAGroupWhereHitCountAt10IsTooSmallForItsOwnMrrNdcgOrRecallMean() throws IOException {
    // Issue #306 review, Befund 3: each of mrr/ndcgAt10/recallAt10 sums, across the group's cases,
    // to at most hitCountAt10 (every contributing case scores at most 1.0) — so a hitCountAt10 that
    // satisfies the ordering check above (0 <= hitCountAt5 <= hitCountAt10 <= n) can still be too
    // small once compared against those three means. Here hitCountAt5 = hitCountAt10 = 1 (ordering
    // holds) but ndcgAt10 = 0.9 over n=2 implies a sum of 1.8, above hitCountAt10 = 1.
    Path file = tempDir.resolve("baseline.json");
    Files.writeString(file, TOO_SMALL_HIT_COUNT_AT_10_JSON);

    assertThatThrownBy(() -> Baseline.load(file))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("hitCountAt10=1")
        .hasMessageContaining("too small for mrr/ndcgAt10/recallAt10");
  }

  private static final String TOO_SMALL_HIT_COUNT_AT_10_JSON =
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
          "goldenCaseCount": 2
        },
        "groups": {
          "overall": {
            "n": 2,
            "hitRateAt5": 0.5,
            "mrr": 0.5,
            "ndcgAt10": 0.9,
            "recallAt10": 0.5,
            "distinctExpectedDocumentSets": 2,
            "recallAt10Ceiling": 1.0,
            "hitCountAt5": 1,
            "hitCountAt10": 1
          }
        },
        "measuredAt": "2026-08-03",
        "notes": "test fixture — hitCountAt10 too small for ndcgAt10"
      }
      """;

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
            "recallAt10Ceiling": 0.9708,
            "hitCountAt5": 63,
            "hitCountAt10": 73
          }
        },
        "measuredAt": "2026-08-03",
        "notes": "test fixture"
      }
      """;
}
