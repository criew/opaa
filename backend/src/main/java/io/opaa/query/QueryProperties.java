package io.opaa.query;

import io.opaa.query.retrieval.RetrievalPipelineProperties;
import io.opaa.query.retrieval.RetrievalStageName;
import io.opaa.query.retrieval.ranking.ChunkEmbeddingLookup;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties of the retrieval pipeline (docs/handbuch/suche.md Abschnitt 10.3 lists
 * the operator-facing form of every value below). Ebene-1 values: overridable per environment
 * variable, absent from every administration surface.
 *
 * @param topK the number of chunks that reach the answer prompt. {@link
 *     RetrievalStageName#RANK_FUSION} caps the fused list at it, or - with reranking active - the
 *     reranker restores the cap after re-scoring the wider window. Default 8; at most 100.
 * @param fetchK candidates each search query retrieves, per search path, before the narrowing
 *     stages work on them. Default 25, normalized to {@code max(25, topK)} when unset so a
 *     deployment that raised {@code topK} alone does not fail the {@code fetchK >= topK} check.
 * @param mmrLambda the relevance/diversity trade-off {@code MmrSelector} applies: a candidate's
 *     score is {@code mmrLambda * relevance - (1 - mmrLambda) * maxSimilarityToAlreadySelected}.
 *     Default {@code 1.0}, where the diversity term vanishes and {@link
 *     RetrievalStageName#MMR_SELECTION} skips the {@link ChunkEmbeddingLookup} round trip. Bound
 *     via {@code @DefaultValue} so an explicit {@code 0.0} is honoured rather than raised.
 * @param similarityThreshold minimum cosine similarity a chunk must reach in the vector path.
 *     Applied inside {@code similaritySearch} itself, so a chunk below it never becomes an MMR
 *     candidate and diversity can never pull it into the selection. Default 0.3.
 * @param queryDecompositionEnabled whether {@link RetrievalStageName#SUB_QUERY_DECOMPOSITION} asks
 *     the LLM to split the question into up to {@link #maxSubQueries} search queries. Default
 *     {@code true}: any failure falls back to the single-query form, so it costs at most one extra
 *     round trip, never a broken query.
 * @param maxSubQueries the upper bound on the search queries the decomposition may return. Default
 *     3; the service truncates beyond it, so an adversarial answer cannot grow retrieval latency
 *     without bound. Each list is narrowed on its own, so the chunk count stays capped at {@link
 *     #topK} regardless.
 * @param maxChunksPerDocument the upper bound on how many chunks of one document {@code
 *     DocumentCompletion} may hold in the final selection. Default 2; {@code 1} is the explicit
 *     opt-out that makes completion the identity.
 * @param fullTextSearchEnabled whether {@link RetrievalStageName#FULL_TEXT_SEARCH} runs and
 *     contributes its lists to the fusion. Default {@code true}. Set to {@code false} for the
 *     {@code vector-only} measurement variant: the stage stays in the chain and says so in the
 *     explanation protocol, unlike switching it off via {@link
 *     RetrievalPipelineProperties#disabledStages()}.
 * @param rerankCandidateCount how many fused candidates {@link RetrievalStageName#RERANK} hands to
 *     the rerank model, and therefore the budget the narrowing stages keep instead of {@link #topK}
 *     whenever reranking runs. Default 50. The window does not extend the reach of retrieval - what
 *     no search returned cannot be reranked - so raising it without raising {@link #fetchK} buys
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
 */
@ConfigurationProperties(prefix = "opaa.query")
public record QueryProperties(
    int topK,
    int fetchK,
    @DefaultValue("1.0") double mmrLambda,
    double similarityThreshold,
    @DefaultValue("true") boolean queryDecompositionEnabled,
    @DefaultValue("3") int maxSubQueries,
    @DefaultValue("2") int maxChunksPerDocument,
    @DefaultValue("true") boolean fullTextSearchEnabled,
    @DefaultValue("50") int rerankCandidateCount,
    @DefaultValue("20") int conversationWindowMessages,
    @DefaultValue("2") int searchWindowTurns) {

  public QueryProperties {
    if (topK <= 0) {
      topK = 8;
    }
    if (topK > 100) {
      throw new IllegalArgumentException("topK must be at most 100, got " + topK);
    }
    if (fetchK <= 0) {
      // max(25, topK), not a flat 25 - see #fetchK's Javadoc.
      fetchK = Math.max(25, topK);
    }
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
}
