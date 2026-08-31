package io.opaa.eval;

import java.util.List;
import java.util.Map;

/**
 * Machine-readable report of the pipeline measurement path (issue #1039,
 * docs/features/retrieval-benchmark.md §1): the result of running every golden case through the
 * same chain a real request takes — sub-question decomposition, one vector search per sub-question,
 * MMR, Reciprocal Rank Fusion, document completion — and stopping before answer generation.
 *
 * <p>Deliberately a separate type from {@link EvaluationReport}, written to a separate file, with
 * its own contract version: the two paths measure different things, are not interconvertible, and a
 * shared report shape would invite exactly the un-labelled side-by-side comparison the
 * specification calls an evaluation error. See {@link #PIPELINE_MEASUREMENT_CONTRACT_VERSION} for
 * why the version is separate from {@link EvaluationReport#CURRENT_MEASUREMENT_CONTRACT_VERSION}.
 */
public record PipelineEvaluationReport(
    int pipelineMeasurementContractVersion,
    String metricWindowNote,
    PipelineRunConfiguration runConfiguration,
    SelectionCoverage selectionCoverage,
    PipelineMetricsAggregate overall,
    Map<String, PipelineMetricsAggregate> byCategory,
    Map<String, PipelineMetricsAggregate> byDifficulty,
    Map<String, PipelineMetricsAggregate> byLanguage,
    List<PipelineQueryResult> worstQueries,
    List<PipelineQueryResult> allQueryResults) {

  /**
   * Version of the pipeline path's own measurement contract (ADR-0012, Nachtrag zum
   * Pipeline-Messpfad). Counted independently of {@link
   * EvaluationReport#CURRENT_MEASUREMENT_CONTRACT_VERSION}, which keeps describing the raw-vector
   * path: that contract is unchanged by this addition, and raising its number would invalidate
   * every committed raw-vector baseline (it is a {@code BaselineComparator} fixed point) for a
   * measurement whose definitions and values did not move at all. Bump this constant whenever a
   * pipeline-path fixed point, window or metric definition changes.
   */
  public static final int PIPELINE_MEASUREMENT_CONTRACT_VERSION = 1;

  /**
   * The fixed points of a pipeline run — everything that must match for two pipeline reports to be
   * comparable. Carries the four production query parameters the specification names (§1, "Folgen
   * für Messvertrag und Baselines"), the decomposition settings, and the same corpus/golden/model
   * identities the raw-vector path pins.
   *
   * @param similarityThresholdNote states that the threshold was actually applied — the one
   *     deliberate difference to the raw-vector path, where the same value is reported but not
   *     applied (ADR-0012 decision 3).
   * @param searchScopeNote records that the harness measures a fixed, complete search scope and
   *     that permission filtering is not a measurement subject.
   * @param chatModel the chat model used for decomposition, or {@code null} when decomposition is
   *     disabled for this run.
   */
  public record PipelineRunConfiguration(
      String domain,
      String embeddingProvider,
      String embeddingModel,
      String embeddingModelDigest,
      String ollamaImage,
      int embeddingDimensions,
      int chunkSize,
      boolean chunkSizeMatchesApplicationDefault,
      int chunkOverlap,
      int fetchK,
      int topK,
      double similarityThreshold,
      String similarityThresholdNote,
      int maxChunksPerDocument,
      double mmrLambda,
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
      int searchScopeLibraryCount,
      String searchScopeNote,
      String runStartedAt,
      double runDurationSeconds,
      // Issue #1076: true when this run's underlying index/queries talked to an external Ollama
      // endpoint (opaa.eval.ollamaBaseUrl) instead of the Testcontainer — see
      // EvaluationReport.RunConfiguration#externalOllamaEndpoint, which the raw-vector path already
      // carries; the pipeline path shares the same run and is therefore equally not
      // baseline-comparable.
      boolean externalOllamaEndpoint) {}

  /**
   * How much the pipeline actually returned, per run. Unlike the raw-vector path's {@link
   * EvaluationReport.DocumentWindowCoverageResult}, falling short of the window is <b>expected</b>
   * here and not an error: the applied similarity threshold can drop a chunk out of the result
   * entirely, so a query legitimately returns fewer than {@code top-k} chunks — or none at all.
   * {@code queriesWithNoChunks} is the number that makes that visible instead of it hiding inside a
   * lowered mean.
   */
  public record SelectionCoverage(
      int queriesEvaluated,
      int queriesWithNoChunks,
      int minChunksReturned,
      int maxChunksReturned,
      double meanChunksReturned,
      double meanDistinctDocumentsReturned) {}

  /**
   * One golden case's pipeline result. Metric components carry their window in their name for the
   * same reason {@link PipelineMetricsAggregate}'s do. {@code reciprocalRankAt8} is the per-case
   * value MRR@8 averages — named like the raw-vector path's per-case {@code reciprocalRank}, since
   * "mean reciprocal rank" of a single case would be a contradiction.
   *
   * @param chunksReturned how many chunks the pipeline selected for this query (at most {@code
   *     top-k}, possibly fewer or zero once the similarity threshold applies).
   * @param distinctDocumentsReturned how many distinct documents those chunks belong to — the
   *     length of the ranked list the metrics were computed over.
   */
  public record PipelineQueryResult(
      String id,
      String query,
      String category,
      String difficulty,
      String language,
      double hitRateAt5,
      double reciprocalRankAt8,
      double ndcgAt8,
      double recallAt8,
      double allExpectedDocumentsHitAt8,
      int chunksReturned,
      int distinctDocumentsReturned,
      List<String> expectedDocuments,
      List<String> rankedFileNames) {}
}
