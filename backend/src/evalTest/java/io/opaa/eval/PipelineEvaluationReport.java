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
    // Issue #1043, docs/features/retrieval-benchmark.md §5 "Zustandsfelder": declared vs. measured
    // case state, at this path's own window. Null for a domain whose golden dataset carries no
    // expected_state fields (see ExpectedStateAudit#evaluate).
    ExpectedStateAudit.Result expectedStateAudit,
    // Issue #1070 (Teil 2): whether the core-field filter itself worked at this path's window, in
    // its two error directions — see MetadataFilterAudit. Null without a single filtered case.
    MetadataFilterAudit.Result metadataFilterAudit,
    List<PipelineQueryResult> worstQueries,
    List<PipelineQueryResult> allQueryResults,
    // Issue #1151: how close to the window edge each group's solved cases sit — report-only, see
    // MarginAggregate's Javadoc for why this is deliberately not part of
    // pipelineMeasurementContractVersion or PipelineBaseline.
    MarginAggregate overallMargins,
    Map<String, MarginAggregate> marginsByCategory,
    Map<String, MarginAggregate> marginsByDifficulty,
    Map<String, MarginAggregate> marginsByLanguage) {

  /**
   * Version of the pipeline path's own measurement contract (ADR-0012, Nachtrag zum
   * Pipeline-Messpfad). Counted independently of {@link
   * EvaluationReport#CURRENT_MEASUREMENT_CONTRACT_VERSION}, which keeps describing the raw-vector
   * path: that contract is unchanged by this addition, and raising its number would invalidate
   * every committed raw-vector baseline (it is a {@code BaselineComparator} fixed point) for a
   * measurement whose definitions and values did not move at all. Bump this constant whenever a
   * pipeline-path fixed point, window or metric definition changes.
   *
   * <p>Version 2 (issue #1040, ADR-0012 Nachtrag zu den Pipeline-Baselines): the five query
   * parameters that version 1 reported but left unchecked — {@code fetch-k}, {@code
   * similarity-threshold}, {@code max-chunks-per-document}, {@code mmr-lambda}, {@code
   * max-sub-queries} — became enforced fixed points of the baseline validity check. That widens
   * what "the same measurement" means, so it is a contract change by decision 6 of this ADR; it
   * costs nothing here because no pipeline baseline existed under version 1.
   *
   * <p>Version 3 (issue #1049, ADR-0012 Nachtrag Volltextpfad): the lexical search path became an
   * input of the fusion and therefore moves the selection. Two new fixed points record it — {@code
   * fullTextSearchEnabled} (the path's switch) and the measured library's full-text index state
   * (named {@code fullTextIndexComplete} since version 7). Without them a hybrid run and a
   * vector-only run would carry the identical {@code runConfiguration} fingerprint and the
   * difference between them would be booked against the committed baseline as a code change.
   *
   * <p>Version 4 (issue #1144, ADR-0012 Nachtrag): {@code ingestionPipelineFingerprint} became a
   * fixed point — see {@link IngestionPipelineFingerprint}'s Javadoc for what it records and why
   * {@code corpusManifestSha256} alone did not already cover it.
   *
   * <p>Version 5 (issue #1164, PR #1201 review): {@code MailDocumentPipeline#version()} moved 2 → 3
   * (mail_date truncated to whole seconds for lexicographic sortability), which shifted every
   * committed baseline's {@code ingestionPipelineFingerprint} even though no corpus in this
   * repository routes a document through that pipeline - the fingerprint is a collective fixed
   * point over every registered pipeline, not only the ones a given corpus actually reaches.
   *
   * <p>Version 6 (issue #1183, ADR-0022): {@code MailDocumentPipeline#version()} moved 3 → 4 (an
   * attachment is now a separate, generalized-attachment-path {@code Document} instead of a chunk
   * nested under its Mail parent) - same collective-fingerprint reasoning as version 5 above.
   *
   * <p>Version 7 (issue #1270): the fixed point {@code fullTextBackfillComplete} is named {@code
   * fullTextIndexComplete}. The full-text backfill and its per-library gate are gone; what is
   * measured is unchanged - whether the measured library's full-text index was complete - so this
   * is a pure fixed-point rename, no re-measurement (see eval/baseline/README.md).
   *
   * <p>Version 8 (issue #1070, Teil 2, ADR-0012 Nachtrag Metadatenfilter): {@code
   * metadataFilterEnabled} became a fixed point - each golden case's {@code filter} is carried into
   * the pipeline run through {@code
   * QueryService#retrieveRelevantChunksInGivenScopeWithDecomposition}, where the {@code
   * METADATA_FILTER} stage applies it in both search paths. Unlike versions 4 to 7 this is not a
   * fingerprint-only bump: the filter moves the measured selection of the {@code metadata_filter}
   * class, and the {@code verwaltung} baseline was re-drawn.
   */
  public static final int PIPELINE_MEASUREMENT_CONTRACT_VERSION = 10;

  /**
   * The fixed points of a pipeline run — everything that must match for two pipeline reports to be
   * comparable. Carries the four production query parameters the specification names (§1, "Folgen
   * für Messvertrag und Baselines"), the decomposition settings, and the same corpus/golden/model
   * identities the raw-vector path pins.
   *
   * @param similarityThresholdNote states that the threshold was actually applied — the one
   *     deliberate difference to the raw-vector path, where the same value is reported but not
   *     applied (ADR-0012 decision 3). It describes the vector path: {@code
   *     opaa.query.similarity-threshold} is a property of vector distance and has no counterpart in
   *     the lexical path.
   * @param fullTextSearchEnabled whether the lexical search path contributed its ranked lists to
   *     the fusion in this run (issue #1049). A measured dimension since that path moves the
   *     selection — a {@code vector-only} run would otherwise be indistinguishable from a hybrid
   *     one here and its numbers would be judged against the committed baseline as a code change.
   * @param fullTextIndexComplete whether every chunk of the measured library carried its full-text
   *     row, i.e. whether the lexical path could contribute its full share - a chunk missing from
   *     that index is invisible to it. {@code true} with {@code fullTextSearchEnabled = false} is
   *     not a contradiction: the index was ready, the path was switched off.
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
      String ingestionPipelineFingerprint,
      // Issue #1070 (Teil 2): whether every golden case's filter was carried into the pipeline
      // run, so both search paths applied it inside their queries. A fixed point: the
      // metadata_filter class measures something else without it.
      boolean metadataFilterEnabled,
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
   * @param subQueries the search queries decomposition (or its single-query fallback) produced for
   *     this case in this run — see {@link io.opaa.query.QueryService.RetrievalWithDecomposition}.
   *     Recorded on every run, not only decomposition-enabled ones, so a multi-run comparison
   *     (issue #1044, docs/features/retrieval-benchmark.md §3) can tell whether decomposition
   *     produced a different sub-query set for the same question across runs.
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
      // Issue #1151: the margin (RetrievalMetrics#marginAtK) this case's first relevant hit had
      // against each window; null when no expected document appears anywhere in rankedFileNames.
      Integer hitRateMargin,
      Integer rankingMargin,
      int chunksReturned,
      int distinctDocumentsReturned,
      List<String> expectedDocuments,
      List<String> rankedFileNames,
      List<String> subQueries) {}
}
