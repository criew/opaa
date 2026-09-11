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
   * and {@code fullTextIndexUpToDate}. The lexical path moves the selection, so a run in which it
   * was switched off — or in which the measured library's full-text index sat below the current
   * version — is measuring a different configuration, not scoring worse.
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
      boolean fullTextIndexUpToDate,
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
      String ingestionPipelineFingerprint,
      // Issue #1070 (Teil 2): whether each golden case's filter was carried into the pipeline
      // run — see PipelineEvaluationReport.PipelineRunConfiguration#metadataFilterEnabled. Boxed
      // on purpose: a baseline file predating the field loads as null and is then reported as
      // incomparable ("null" vs. "true") instead of silently reading as "measured without filters".
      Boolean metadataFilterEnabled) {}

  public static PipelineBaseline load(Path file) throws IOException {
    PipelineBaseline baseline =
        JsonMapper.builder().build().readValue(Files.readString(file), PipelineBaseline.class);
    validate(baseline, file);
    return baseline;
  }

  /**
   * The shared load-time guards of {@link PipelineGroupInvariants} - see there for the derivation
   * of each inequality and for why they live in one place rather than once per baseline type.
   */
  private static void validate(PipelineBaseline baseline, Path file) {
    PipelineGroupInvariants.validateGroups(baseline.groups(), file, "Pipeline baseline");
  }
}
