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
    // Issue #721 code review, Wichtig 1: ADR-0012 §8 and the issue's acceptance criteria both
    // promise an explicit report of whether the document-bound window was actually reached, not
    // just a silently-computed-and-discarded value — see DocumentWindowCoverageResult's Javadoc.
    DocumentWindowCoverageResult documentWindowCoverage,
    // Issue #721 code review, Wichtig 3: whether every applicable answer_span actually resolved to
    // a chunk of one of its expected_documents — see AnswerSpanResolutionResult's Javadoc.
    AnswerSpanResolutionResult answerSpanResolution,
    // Issue #1043, docs/features/retrieval-benchmark.md §5 "Zustandsfelder": declared vs. measured
    // case state. Null for a domain whose golden dataset carries no expected_state fields —
    // absent, not "audited and clean" (see ExpectedStateAudit#evaluate).
    ExpectedStateAudit.Result expectedStateAudit,
    // Issue #1070 (Teil 2): whether the core-field filter itself worked, in its two error
    // directions — see MetadataFilterAudit. Null for a domain without a single filtered case.
    MetadataFilterAudit.Result metadataFilterAudit,
    List<WorstQuery> worstQueries,
    List<WorstQuery> allQueryResults,
    // Issue #1151: how close to the window edge each group's solved cases sit — report-only,
    // deliberately not part of measurementContractVersion or any Baseline (see MarginAggregate's
    // Javadoc). Added after allQueryResults, at the end, so an existing committed report JSON grows
    // a field instead of reordering — irrelevant to Jackson but keeps a textual diff minimal.
    MarginAggregate overallMargins,
    Map<String, MarginAggregate> marginsByCategory,
    Map<String, MarginAggregate> marginsByDifficulty,
    Map<String, MarginAggregate> marginsByLanguage) {

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
   *
   * <p><b>Bumped to 3 by issue #1144 (ADR-0012 Nachtrag):</b> {@code ingestionPipelineFingerprint}
   * became a fixed point — see {@link IngestionPipelineFingerprint}'s Javadoc for what it records
   * and why {@code corpusManifestSha256} alone did not already cover it.
   *
   * <p><b>Bumped to 4 by issue #1164 (PR #1201 review):</b> {@code MailDocumentPipeline#version()}
   * moved 2 → 3 (mail_date truncated to whole seconds for lexicographic sortability), which shifted
   * every committed baseline's {@code ingestionPipelineFingerprint} even though no corpus in this
   * repository routes a document through that pipeline — the fingerprint is a collective fixed
   * point over every registered pipeline (see {@link IngestionPipelineFingerprint}'s Javadoc), not
   * only the ones a given corpus actually reaches.
   *
   * <p><b>Bumped to 5 by issue #1183 (ADR-0022):</b> {@code MailDocumentPipeline#version()} moved 3
   * → 4 (an attachment is now a separate, generalized-attachment-path {@code Document} instead of a
   * chunk nested under its Mail parent) - the same collective-fingerprint reasoning as the #1164
   * bump above, not a corpus routing change (no corpus in this repository routes a document through
   * {@code MailDocumentPipeline}).
   *
   * <p><b>Bumped to 6 by issue #1070 (Teil 2, ADR-0012 Nachtrag Metadatenfilter):</b> {@code
   * metadataFilterEnabled} became a fixed point - the harness applies each golden case's {@code
   * filter} inside {@code similaritySearch}, so a run with and one without the filter measure
   * different things for the {@code metadata_filter} class. Unlike the two fingerprint bumps above
   * this one moves measured values (the {@code verwaltung} baselines were re-drawn).
   */
  public static final int CURRENT_MEASUREMENT_CONTRACT_VERSION = 7;

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
      // Kept as a separate field from chunkTopK, not removed, for two reasons (issue #721 code
      // review, "Klein"): (1) ADR-0012 decision 3 already named searchTopK a fixed point before
      // #721, and every historical report/baseline written under measurement-contract version 1
      // uses that name for "the literal topK argument passed to similaritySearch" — chunkTopK is
      // the #721-introduced name for the same value, derived rather than independently chosen. (2)
      // The two are only guaranteed equal *by construction* of the harness's own call site (it
      // passes DOMAIN.chunkTopK() as both), not by any invariant the type system enforces; keeping
      // both names lets a future refactor that decouples them (unlikely, but not impossible) show
      // up as a value divergence instead of being silently absorbed by one field standing in for
      // two concerns.
      int searchTopK,
      double productionSimilarityThreshold,
      String similarityThresholdNote,
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
      // Issue #1070 (Teil 2): whether every golden case's filter was applied inside the search
      // (as a Filter.Expression on similaritySearch, built by MetadataFilterExpressions exactly as
      // the production vector path builds it). A fixed point: the metadata_filter class measures
      // something else without it.
      boolean metadataFilterEnabled,
      String runStartedAt,
      double runDurationSeconds,
      // Issue #1076: true when this run talked to an external Ollama endpoint
      // (opaa.eval.ollamaBaseUrl) instead of the Testcontainer — such a run is not
      // baseline-comparable (CPU/GPU embedding kernels are not guaranteed bit-identical, analogous
      // to the -Dopaa.eval.allowGpu opt-out), see eval/README.md, "Externer Ollama-Endpunkt".
      boolean externalOllamaEndpoint) {}

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
   * Whether every query's chunk-bound search actually surfaced {@code documentTopK} distinct
   * documents after deduplication (issue #721, ADR-0012 §8) — the explicit report the acceptance
   * criteria promise, instead of {@link DocumentRanking.DocumentWindowResult} being computed per
   * query and then discarded. {@code queriesBelowDocumentTopK} counts queries where {@link
   * DocumentRanking.DocumentWindowResult#reachedDocumentTopK()} was {@code false} — expected to be
   * zero whenever the corpus has comfortably more than {@code documentTopK} documents
   * (comic-characters: 1448 documents against {@code documentTopK=10}), and asserted as such by the
   * harness for that domain. {@code minDistinctDocumentsReached} is the smallest per-query {@code
   * distinctDocumentsReached} seen across the whole run — a single number that makes "did the
   * window ever come up short, and by how much" readable without scanning {@code allQueryResults}.
   *
   * <p>Counted over the <b>unfiltered</b> queries only (issue #1070): a case measured with a
   * core-field filter may legitimately have fewer than {@code documentTopK} documents left in the
   * whole store (a 2023 date window over a corpus with nine 2023 documents), which is the filter
   * working, not the window coming up short.
   */
  public record DocumentWindowCoverageResult(
      int queriesEvaluated, int queriesBelowDocumentTopK, int minDistinctDocumentsReached) {

    public boolean alwaysReachedDocumentTopK() {
      return queriesBelowDocumentTopK == 0;
    }
  }

  /**
   * Whether every applicable {@code answer_span} (issue #721) actually resolved to at least one
   * chunk of at least one of its {@code expected_documents} (issue #721 code review, Wichtig 3). An
   * unresolved span — a typo, a whitespace difference {@link SpanMatcher} does not absorb, or a
   * chunking-parameter change that pushed the span across a chunk boundary — is numerically
   * indistinguishable from a genuine retrieval failure: both make {@link
   * ChunkAnswerSpanMetrics#evaluate} return {@code spanChunkRank=-1}. Left unchecked, a chunking
   * change could look like a chunk-level regression when it is actually a broken fixture, exactly
   * the failure mode {@code boundary_span} golden cases (#234) are meant to detect on purpose — the
   * measurement would be silently wrong about which of the two happened.
   *
   * <p>The harness treats a non-empty {@code unresolvedCaseIds} as a hard abort for any domain that
   * declares at least one {@code answer_span} case (mirrors {@link ChunkCountInvariantResult}: a
   * broken measurement precondition, not a tolerance case). For {@code comic-characters} ({@code
   * applicableCases=0}) this can never fire.
   */
  public record AnswerSpanResolutionResult(int applicableCases, List<String> unresolvedCaseIds) {

    public boolean allResolved() {
      return unresolvedCaseIds.isEmpty();
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
      // Issue #913: "Recall pro Teilthema" — 1.0 only if every expected document was retrieved, see
      // RetrievalMetrics#allExpectedDocumentsHitAtK. Carried per-query (not just per group) so
      // allQueryResults lets a reader verify a multi_topic case's coverage without recomputing it
      // from rankedFileNames/expectedDocuments.
      double allExpectedDocumentsHitAt10,
      // Issue #1151: the margin (RetrievalMetrics#marginAtK) this case's first relevant hit had
      // against each window; null when no expected document appears anywhere in rankedFileNames.
      Integer hitRateMarginAt5,
      Integer rankingMarginAt10,
      List<String> expectedDocuments,
      List<String> rankedFileNames) {}
}
