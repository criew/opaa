package io.opaa.eval;

import java.util.List;
import java.util.Map;

/** Machine-readable retrieval-evaluation report (issue #227 acceptance criteria). */
public record EvaluationReport(
    int measurementContractVersion,
    RunConfiguration runConfiguration,
    ChunkCountInvariantResult chunkCountInvariant,
    DatasetNotes datasetNotes,
    MetricsAggregate overall,
    Map<String, MetricsAggregate> byCategory,
    Map<String, MetricsAggregate> byDifficulty,
    Map<String, MetricsAggregate> byLanguage,
    // Issue #721, ADR-0012 Nachtrag: the chunk-level answer-span metric family, overall only (not
    // broken down by category/difficulty/language — the per-query detail in allQueryResults already
    // lets a reader build any cross-tabulation needed). NOT_APPLICABLE (applicableCases=0) for a
    // domain whose golden cases carry no answer_span, i.e. comic-characters today.
    ChunkAnswerSpanMetrics.Aggregate answerSpanOverall,
    List<WorstQuery> worstQueries,
    List<WorstQuery> allQueryResults) {

  /**
   * Version of the measurement contract this report was produced under — see ADR-0012. Bump this
   * whenever a change to gain function, IDCG basis, k-windows, threshold handling or the
   * micro/macro averaging choice would make historical reports incomparable to new ones.
   *
   * <p><b>Bumped to 2 by issue #721 (ADR-0012 Nachtrag):</b> the k-window is now explicitly
   * document-bound rather than chunk-bound (see {@link DocumentRanking}), and a second metric
   * family (chunk-level answer-span) was added. For a one-chunk-per-document corpus the two windows
   * coincide (see {@code EvalDomainConfig#COMIC_CHARACTERS}), so this version bump changes report
   * *shape*, not comic-characters' measured *values* — see the PR description's before/after
   * comparison for the empirical confirmation.
   */
  public static final int CURRENT_MEASUREMENT_CONTRACT_VERSION = 2;

  /** Configuration of the measured run — lets a reader trace a number back to what produced it. */
  public record RunConfiguration(
      String embeddingProvider,
      String embeddingModel,
      String embeddingModelDigest,
      String ollamaImage,
      int embeddingDimensions,
      int chunkSize,
      boolean chunkSizeMatchesApplicationDefault,
      // Issue #374: recorded so two reports can be told apart by the chunk overlap they were
      // measured with. On a corpus where the Ein-Chunk-Invariante holds this value cannot change
      // anything — overlap only exists between chunks — which is itself worth having in writing.
      int chunkOverlap,
      // Issue #721, ADR-0012 Nachtrag: the k-window is now explicitly document-bound. documentTopK
      // is the number of distinct documents the ranking metrics are computed over (10, unchanged in
      // value from the pre-#721 chunk-bound topK); chunkTopK is the actual similaritySearch topK
      // used to reach that many distinct documents after deduplication (DocumentRanking). For
      // comic-characters chunkTopK == documentTopK == 10, because maxChunksPerDocument == 1.
      int documentTopK,
      int chunkTopK,
      int searchTopK,
      double productionSimilarityThreshold,
      String similarityThresholdNote,
      String pgvectorIndexType,
      String corpusManifestSha256,
      int corpusDocumentCount,
      String goldenDatasetFile,
      String goldenDatasetSha256,
      int goldenCaseCount,
      String runStartedAt,
      double runDurationSeconds) {}

  /**
   * The chunk-count invariant check (ADR-0010, made a per-domain property by its #721 Nachtrag):
   * every corpus document must satisfy the domain's declared {@link ChunkCountExpectation} after
   * the real, production-configured {@code TokenTextSplitter} runs. This is the beweiskräftige
   * (proof-carrying) check ADR-0010 assigns to this harness — the generator's own byte-size guard
   * is only a cheap approximation.
   */
  public record ChunkCountInvariantResult(
      String expectationDescription, int documentsChecked, List<Violation> violations) {

    public record Violation(String fileName, int chunkCount) {}

    public boolean holds() {
      return violations.isEmpty();
    }
  }

  /**
   * Calibration notes (see docs/features/search-quality-evaluation.md review history): the case
   * count overstates the number of independent observations because several cases share an
   * expected-document set (e.g. every crosslingual case is a German twin of an English one, by
   * construction of the generator).
   */
  public record DatasetNotes(int caseCount, int distinctExpectedDocumentSets, String note) {}

  public record WorstQuery(
      String id,
      String query,
      String category,
      String difficulty,
      String language,
      double ndcgAt10,
      double hitRateAt5,
      double reciprocalRank,
      double recallAt10,
      List<String> expectedDocuments,
      List<String> rankedFileNames) {}
}
