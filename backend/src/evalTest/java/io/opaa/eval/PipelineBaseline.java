package io.opaa.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * The committed pipeline-path baseline (issue #1040), checked into {@code
 * eval/baseline/pipeline-<domain>.json}. The counterpart of {@link Baseline} for the measurement
 * path that runs through the production query pipeline instead of {@code
 * VectorStore.similaritySearch} directly.
 *
 * <p><b>A separate type and a separate file per path and domain, never a shared one.</b> The two
 * paths measure at different windows (@10 without a similarity threshold vs. @8 with it applied),
 * are not interconvertible, and carry independently counted contract versions (ADR-0012, Nachtrag
 * Pipeline-Messpfad, decision 16). Sharing a schema would make it possible for a pipeline
 * re-measurement to be written over a raw-vector baseline — the one outcome {@code
 * docs/features/retrieval-benchmark.md} §1 rules out before any code is written.
 *
 * <p>See {@code eval/baseline/README.md} for the update procedure; the tolerance formula and the
 * error criterion are ADR-0013's, unchanged and literally shared with the raw-vector path (see
 * {@link PipelineBaselineComparator}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PipelineBaseline(
    int pipelineMeasurementContractVersion,
    FixedPoints fixedPoints,
    Map<String, PipelineMetricsAggregate> groups,
    String measuredAt,
    Baseline.Provenance provenance,
    String notes) {

  /**
   * The values that define what the pipeline path measured, as opposed to how well it scored. Any
   * drift here means "this baseline no longer applies", not "retrieval got worse" — see {@link
   * PipelineBaselineComparator#compare}.
   *
   * <p>Carries the full set of pipeline fixed points from ADR-0012, Nachtrag, decision 13: the
   * query parameters {@code fetch-k}, {@code top-k}, {@code similarity-threshold}, {@code
   * max-chunks-per-document}, {@code mmr-lambda}, the decomposition settings {@code
   * query-decomposition-enabled} and {@code max-sub-queries}, and the chat model used when
   * decomposition is active. Until the first pipeline baseline existed, those values were reported
   * but not checked (that ADR's "Ebenfalls offen" paragraph); with a baseline to compare against,
   * an unnoticed {@code mmr-lambda} change would silently redefine what the committed numbers mean,
   * so all of them are validity fields here.
   *
   * <p>Since issue #1049 that set includes the two full-text fields: {@code fullTextSearchEnabled}
   * and {@code fullTextIndexComplete}. The lexical path moves the selection, so a run in which it
   * was switched off — or in which the measured library's full-text index was incomplete — is
   * measuring a different configuration, not scoring worse.
   *
   * <p>Since issue #1144 it also includes {@code ingestionPipelineFingerprint} — see {@link
   * IngestionPipelineFingerprint}'s Javadoc for what it records and why {@code
   * corpusManifestSha256} alone does not already cover it.
   *
   * @param chatModel {@code null} while the harness measures the {@code decomposition-off} variant
   *     — a value here would claim a model took part in the run that did not.
   * @param hitRateK the two metric windows the report's field names state literally; a change makes
   *     every committed number mean something else and is a contract change, not a score change.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FixedPoints(
      String embeddingModel,
      String embeddingModelDigest,
      int embeddingDimensions,
      int chunkSize,
      boolean chunkSizeMatchesApplicationDefault,
      int chunkOverlap,
      int fetchK,
      int topK,
      double similarityThreshold,
      int maxChunksPerDocument,
      double mmrLambda,
      boolean fullTextSearchEnabled,
      boolean fullTextIndexComplete,
      boolean queryDecompositionEnabled,
      int maxSubQueries,
      String chatModel,
      int hitRateK,
      int rankingK,
      String pgvectorIndexType,
      String corpusManifestSha256,
      int corpusDocumentCount,
      String goldenDatasetFile,
      String goldenDatasetSha256,
      int goldenCaseCount,
      // Issue #1144: under which ingestion pipeline versions (all registered, not just the ones
      // this corpus routes through) this was measured — see IngestionPipelineFingerprint's
      // Javadoc for why corpusManifestSha256 alone does not answer that question.
      String ingestionPipelineFingerprint) {}

  public static PipelineBaseline load(Path file) throws IOException {
    PipelineBaseline baseline =
        JsonMapper.builder().build().readValue(Files.readString(file), PipelineBaseline.class);
    validate(baseline, file);
    return baseline;
  }

  /**
   * The same load-time guards {@link Baseline#load} applies, at this path's windows: a hand-edited
   * baseline whose {@code distinctExpectedDocumentSets} or case counts are missing or wrong would
   * otherwise silently widen (or under-protect) a group's tolerance instead of failing loudly. See
   * {@code Baseline}'s corresponding methods for the derivation of each inequality; they hold here
   * for the same reasons, with {@code hitCountAt8} taking {@code hitCountAt10}'s role — the ranked
   * list of this path never has more than {@link PipelineMetricsAggregate#RANKING_K} entries, so "a
   * hit exists in the list" and "a hit exists in the top 8" are the identical event.
   */
  private static void validate(PipelineBaseline baseline, Path file) {
    baseline
        .groups()
        .forEach(
            (key, aggregate) -> {
              if (aggregate.distinctExpectedDocumentSets() <= 0) {
                throw new IllegalStateException(
                    "Pipeline baseline group '"
                        + key
                        + "' in "
                        + file.toAbsolutePath()
                        + " has distinctExpectedDocumentSets="
                        + aggregate.distinctExpectedDocumentSets()
                        + " (missing or zero) — this would silently widen that group's tolerance "
                        + "to the loosest possible value instead of failing loudly. Fix the field "
                        + "in the baseline file.");
              }
              validateCaseCounts(key, aggregate, file);
            });
  }

  private static void validateCaseCounts(
      String key, PipelineMetricsAggregate aggregate, Path file) {
    long expectedHitCountAt5 = Math.round(aggregate.hitRateAt5() * aggregate.n());
    if (aggregate.hitCountAt5() != expectedHitCountAt5) {
      throw new IllegalStateException(
          "Pipeline baseline group '"
              + key
              + "' in "
              + file.toAbsolutePath()
              + " has hitCountAt5="
              + aggregate.hitCountAt5()
              + " but hitRateAt5="
              + aggregate.hitRateAt5()
              + " over n="
              + aggregate.n()
              + " implies exactly "
              + expectedHitCountAt5
              + " (hitRateAt5 is binary per case, so its mean times n is the exact hit count) — "
              + "fix the field in the baseline file.");
    }
    if (aggregate.hitCountAt5() < 0
        || aggregate.hitCountAt5() > aggregate.hitCountAt8()
        || aggregate.hitCountAt8() > aggregate.n()) {
      throw new IllegalStateException(
          "Pipeline baseline group '"
              + key
              + "' in "
              + file.toAbsolutePath()
              + " violates 0 <= hitCountAt5 <= hitCountAt8 <= n (hitCountAt5="
              + aggregate.hitCountAt5()
              + ", hitCountAt8="
              + aggregate.hitCountAt8()
              + ", n="
              + aggregate.n()
              + ") — every top-5 hit is also a top-8 hit, and neither count can exceed the case "
              + "count. Fix the field(s) in the baseline file.");
    }
    double impliedMinimumHits =
        Math.max(aggregate.mrrAt8(), Math.max(aggregate.ndcgAt8(), aggregate.recallAt8()))
            * aggregate.n();
    if (aggregate.hitCountAt8() + 0.1 < impliedMinimumHits) {
      throw new IllegalStateException(
          "Pipeline baseline group '"
              + key
              + "' in "
              + file.toAbsolutePath()
              + " has hitCountAt8="
              + aggregate.hitCountAt8()
              + ", too small for mrrAt8/ndcgAt8/recallAt8 over n="
              + aggregate.n()
              + " (each case contributes at most 1.0 to every one of those three metrics, so their "
              + "sum can never exceed hitCountAt8) — fix the field(s) in the baseline file.");
    }
    double allTopicsHit = aggregate.allExpectedDocumentsHitAt8();
    double roundingEpsilon = 0.0005 * aggregate.n() + 1e-9;
    if (allTopicsHit > aggregate.recallAt8() + roundingEpsilon) {
      throw new IllegalStateException(
          "Pipeline baseline group '"
              + key
              + "' in "
              + file.toAbsolutePath()
              + " has allExpectedDocumentsHitAt8="
              + allTopicsHit
              + " but recallAt8="
              + aggregate.recallAt8()
              + " — a case only counts toward the former if it also fully counts toward the "
              + "latter, so the former can never exceed the latter. Fix the field(s) in the "
              + "baseline file.");
    }
    if (allTopicsHit * aggregate.n() > aggregate.hitCountAt8() + roundingEpsilon) {
      throw new IllegalStateException(
          "Pipeline baseline group '"
              + key
              + "' in "
              + file.toAbsolutePath()
              + " has allExpectedDocumentsHitAt8="
              + allTopicsHit
              + " over n="
              + aggregate.n()
              + ", too large for hitCountAt8="
              + aggregate.hitCountAt8()
              + " (every case counted by allExpectedDocumentsHitAt8 also counts toward "
              + "hitCountAt8) — fix the field(s) in the baseline file.");
    }
  }
}
