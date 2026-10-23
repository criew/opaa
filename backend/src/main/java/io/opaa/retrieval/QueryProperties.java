package io.opaa.retrieval;

import static io.opaa.common.PropertyDefaults.positiveOr;

import io.opaa.api.types.RetrievalStageName;
import io.opaa.compliance.NoDataImpact;
import io.opaa.compliance.PerInstance;
import io.opaa.retrieval.ranking.ChunkEmbeddingLookup;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties of the retrieval pipeline (docs/handbuch/spaces/suche.md Abschnitt 10.3
 * lists the operator-facing form of every value below). Ebene-1 values: overridable per environment
 * variable, absent from every administration surface.
 *
 * @param topK the number of chunks that reach the answer prompt. {@link
 *     RetrievalStageName#RANK_FUSION} caps the fused list at it, or - with reranking active - the
 *     reranker restores the cap after re-scoring the wider window. Default 20; at most 100.
 * @param fetchK candidates each search query retrieves, per search path; all of them reach {@link
 *     RetrievalStageName#RANK_FUSION}, which cuts the fused list to {@link #topK}. Default 25,
 *     normalized to {@code max(25, topK)} when unset so a deployment that raised {@code topK} alone
 *     does not fail the {@code fetchK >= topK} check.
 * @param mmrLambda the relevance/diversity trade-off {@code MmrSelector} applies: a candidate's
 *     score is {@code mmrLambda * relevance - (1 - mmrLambda) * maxSimilarityToAlreadySelected}.
 *     Default {@code 1.0}, where the diversity term vanishes and {@link
 *     RetrievalStageName#MMR_SELECTION} does not run (protocol: {@code DISABLED}), so neither the
 *     reordering nor the {@link ChunkEmbeddingLookup} round trip happens. Bound via
 *     {@code @DefaultValue} so an explicit {@code 0.0} is honoured rather than raised.
 * @param similarityThreshold minimum cosine similarity a chunk must reach in the vector path.
 *     Applied inside {@code similaritySearch} itself, so a chunk below it never becomes an MMR
 *     candidate and diversity can never pull it into the selection. Default 0.3.
 * @param queryDecompositionEnabled whether {@link RetrievalStageName#SUB_QUERY_DECOMPOSITION} asks
 *     the LLM to split the question into up to {@link #maxSubQueries} search queries. Default
 *     {@code true}: any failure falls back to the single-query form, so it costs at most one extra
 *     round trip, never a broken query.
 * @param maxSubQueries the upper bound on the search queries the decomposition may return. Default
 *     3; the service truncates beyond it, so an adversarial answer cannot grow retrieval latency
 *     without bound. The fusion caps the fused list, so the chunk count stays capped at {@link
 *     #topK} regardless.
 * @param maxChunksPerDocument the fill target of {@code DocumentCompletion}: a document already in
 *     the selection is topped up to this many chunks from the candidate pool. Not a cap - a
 *     document holding more is never trimmed to it, it loses chunks only as an eviction source for
 *     another document. Default 1, which makes completion the identity; the neighbour chunks loaded
 *     for the answer context supply surrounding text.
 * @param fullTextSearchEnabled whether {@link RetrievalStageName#FULL_TEXT_SEARCH} runs and
 *     contributes its lists to the fusion. Default {@code true}. Set to {@code false} for the
 *     {@code vector-only} measurement variant: the stage stays in the chain and says so in the
 *     explanation protocol, unlike switching it off via {@link
 *     RetrievalPipelineProperties#disabledStages()}.
 * @param rerankCandidateCount how many fused candidates {@link RetrievalStageName#RERANK} hands to
 *     the rerank model, and therefore the budget the fusion keeps instead of {@link #topK} whenever
 *     reranking runs. Default 50. The window does not extend the reach of retrieval - what no
 *     search returned cannot be reranked - so raising it without raising {@link #fetchK} buys
 *     nothing. {@code 0} switches the stage off through its own parameter; whether reranking runs
 *     at all is additionally governed by the rerank model role's switch ({@code
 *     OPAA_RERANK_ENABLED}), which is an installation decision, not a retrieval parameter.
 * @param conversationWindowMessages the width of the conversation window in messages - what {@code
 *     ConversationMemoryConfiguration}'s {@code ChatMemory} bean retains per conversation and
 *     therefore what the answer prompt carries. Default 20 (10 turns), at least 2, at most 100, and
 *     always even: the window is counted in question/answer pairs, and an odd width would drop an
 *     answer away from its question.
 * @param searchWindowTurns how many of the most recent turns the sub-question decomposition sees of
 *     that window (docs/features/conversation-memory.md, "Bauteil 1"). Default 2; {@code 0} means
 *     "question only". Never more than {@code conversationWindowMessages / 2}: the search must
 *     never see more of the conversation than the answer does.
 * @param fusion how {@link RetrievalStageName#RANK_FUSION} fuses the candidate lists, see {@link
 *     Fusion}.
 */
@PerInstance("search tuning of this process")
@NoDataImpact("search quality; the answer uses only what the person may read")
@ConfigurationProperties(prefix = "opaa.query")
public record QueryProperties(
    int topK,
    int fetchK,
    @DefaultValue("1.0") double mmrLambda,
    double similarityThreshold,
    @DefaultValue("true") @NoDataImpact("one more call to the same chat model")
        boolean queryDecompositionEnabled,
    @DefaultValue("3") int maxSubQueries,
    @DefaultValue("1") int maxChunksPerDocument,
    @DefaultValue("true") @NoDataImpact("a search path over the same index")
        boolean fullTextSearchEnabled,
    @DefaultValue("50") int rerankCandidateCount,
    @DefaultValue("20") int conversationWindowMessages,
    @DefaultValue("2") int searchWindowTurns,
    @DefaultValue Fusion fusion) {

  @ConstructorBinding
  public QueryProperties {
    topK = positiveOr(topK, 20);
    if (fusion == null) {
      fusion = Fusion.DEFAULT;
    }
    if (topK > 100) {
      throw new IllegalArgumentException("topK must be at most 100, got " + topK);
    }
    fetchK = positiveOr(fetchK, Math.max(25, topK)); // max(25, topK), see #fetchK's Javadoc
    if (fetchK > 200) {
      throw new IllegalArgumentException("fetchK must be at most 200, got " + fetchK);
    }
    if (fetchK < topK) {
      throw new IllegalArgumentException(
          "fetchK must be at least topK, got fetchK=" + fetchK + " topK=" + topK);
    }
    if (mmrLambda < 0.0 || mmrLambda > 1.0) {
      throw new IllegalArgumentException("mmrLambda must be between 0.0 and 1.0, got " + mmrLambda);
    }
    if (similarityThreshold < 0.0 || similarityThreshold > 1.0) {
      throw new IllegalArgumentException(
          "similarityThreshold must be between 0.0 and 1.0, got " + similarityThreshold);
    }
    if (maxSubQueries <= 0 || maxSubQueries > 10) {
      throw new IllegalArgumentException(
          "maxSubQueries must be between 1 and 10, got " + maxSubQueries);
    }
    if (maxChunksPerDocument <= 0 || maxChunksPerDocument > 10) {
      throw new IllegalArgumentException(
          "maxChunksPerDocument must be between 1 and 10, got " + maxChunksPerDocument);
    }
    // Deliberately not checked against fetchK: the window's reach is fetchK per list times the
    // lists in flight, a per-query quantity this constructor cannot know.
    if (rerankCandidateCount < 0 || rerankCandidateCount > 200) {
      throw new IllegalArgumentException(
          "rerankCandidateCount must be between 0 and 200, got " + rerankCandidateCount);
    }
    if (conversationWindowMessages < 2 || conversationWindowMessages > 100) {
      throw new IllegalArgumentException(
          "conversationWindowMessages must be between 2 and 100, got "
              + conversationWindowMessages);
    }
    if (conversationWindowMessages % 2 != 0) {
      throw new IllegalArgumentException(
          "conversationWindowMessages must be even, got " + conversationWindowMessages);
    }
    if (searchWindowTurns < 0 || searchWindowTurns > conversationWindowMessages / 2) {
      throw new IllegalArgumentException(
          "searchWindowTurns must be between 0 and conversationWindowMessages / 2 ("
              + conversationWindowMessages / 2
              + "), got "
              + searchWindowTurns);
    }
  }

  /** The parameter set with the shipped {@link Fusion}. */
  public QueryProperties(
      int topK,
      int fetchK,
      double mmrLambda,
      double similarityThreshold,
      boolean queryDecompositionEnabled,
      int maxSubQueries,
      int maxChunksPerDocument,
      boolean fullTextSearchEnabled,
      int rerankCandidateCount,
      int conversationWindowMessages,
      int searchWindowTurns) {
    this(
        topK,
        fetchK,
        mmrLambda,
        similarityThreshold,
        queryDecompositionEnabled,
        maxSubQueries,
        maxChunksPerDocument,
        fullTextSearchEnabled,
        rerankCandidateCount,
        conversationWindowMessages,
        searchWindowTurns,
        Fusion.DEFAULT);
  }

  /** The same parameter set with another {@link Fusion}. */
  public QueryProperties withFusion(Fusion otherFusion) {
    return new QueryProperties(
        topK,
        fetchK,
        mmrLambda,
        similarityThreshold,
        queryDecompositionEnabled,
        maxSubQueries,
        maxChunksPerDocument,
        fullTextSearchEnabled,
        rerankCandidateCount,
        conversationWindowMessages,
        searchWindowTurns,
        otherFusion);
  }

  /**
   * How the fusion turns the candidate lists into the selection ({@code opaa.query.fusion.*},
   * docs/features/retrieval-algorithm.md, step 5).
   *
   * @param method how the lists of one sub-query are scored. Default {@link FusionMethod#RRF}:
   *     tuning-free, while the weight {@link FusionMethod#SCORE} needs has not been measured.
   * @param subQueryQuota whether each sub-query is fused on its own and the selection is filled
   *     round-robin across them, so a candidate leading its own sub-query is not displaced by
   *     candidates that several near-identical sub-queries share. Default {@code true}; with one
   *     search query it is the identity.
   * @param vectorWeight the vector path's share in {@link FusionMethod#SCORE}, between 0 and 1; the
   *     lexical path gets the remainder. Default 0.7. Ignored by {@link FusionMethod#RRF}.
   * @param guaranteedPerList how many of the best candidates of every list (one per sub-query and
   *     search path) enter the selection regardless of their fused rank; the rest is filled in
   *     fused order, the budget stays the cap. Default 1, between 0 and 10; {@code 0} switches the
   *     guarantee off.
   */
  public record Fusion(
      @DefaultValue("RRF") FusionMethod method,
      @DefaultValue("true") boolean subQueryQuota,
      @DefaultValue("0.7") double vectorWeight,
      @DefaultValue("1") int guaranteedPerList) {

    /** The shipped fusion. */
    public static final Fusion DEFAULT = new Fusion(FusionMethod.RRF, true, 0.7, 1);

    public Fusion {
      if (method == null) {
        method = FusionMethod.RRF;
      }
      if (vectorWeight < 0.0 || vectorWeight > 1.0) {
        throw new IllegalArgumentException(
            "fusion.vectorWeight must be between 0.0 and 1.0, got " + vectorWeight);
      }
      if (guaranteedPerList < 0 || guaranteedPerList > 10) {
        throw new IllegalArgumentException(
            "fusion.guaranteedPerList must be between 0 and 10, got " + guaranteedPerList);
      }
    }
  }
}
