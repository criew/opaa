package io.opaa.eval;

import java.util.List;
import java.util.Map;

/** Machine-readable retrieval-evaluation report (issue #227 acceptance criteria). */
public record EvaluationReport(
    int measurementContractVersion,
    RunConfiguration runConfiguration,
    OneChunkInvariantResult oneChunkInvariant,
    DatasetNotes datasetNotes,
    MetricsAggregate overall,
    Map<String, MetricsAggregate> byCategory,
    Map<String, MetricsAggregate> byDifficulty,
    Map<String, MetricsAggregate> byLanguage,
    List<WorstQuery> worstQueries,
    List<WorstQuery> allQueryResults) {

  /**
   * Version of the measurement contract this report was produced under — see ADR-0012. Bump this
   * whenever a change to gain function, IDCG basis, k-windows, threshold handling or the
   * micro/macro averaging choice would make historical reports incomparable to new ones.
   */
  public static final int CURRENT_MEASUREMENT_CONTRACT_VERSION = 1;

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
   * The Ein-Chunk-Invariante check (ADR-0010): every corpus document must produce exactly one chunk
   * after the real, production-configured {@code TokenTextSplitter} runs. This is the
   * beweiskräftige (proof-carrying) check ADR-0010 assigns to this harness — the generator's own
   * byte-size guard is only a cheap approximation.
   */
  public record OneChunkInvariantResult(int documentsChecked, List<Violation> violations) {

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
