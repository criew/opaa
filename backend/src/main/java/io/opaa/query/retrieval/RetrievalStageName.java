package io.opaa.query.retrieval;

import io.opaa.query.QueryProperties;
import io.opaa.query.retrieval.search.FullTextIndexCompleteness;
import io.opaa.query.retrieval.search.QueryDecompositionService;

/**
 * The named stages of the retrieval pipeline (docs/handbuch/suche.md Abschnitt 4,
 * docs/features/retrieval-algorithm.md). The constant order is documentation only - the order
 * stages actually run in is the order {@code QueryConfiguration#retrievalPipeline} registers them
 * in, which is the one place it is decided.
 *
 * <p>Every stage except {@link #SEARCH_SCOPE} can be switched off via {@link
 * RetrievalPipelineProperties}, and a switched-off stage is the identity: the pipeline then runs
 * exactly as it would without that stage in the chain, not as it would with the stage present but
 * neutralized. What "without this stage" means is documented on the constant itself.
 */
public enum RetrievalStageName {

  /**
   * Turns the caller-supplied, already permission-resolved search scope into the {@code library_id
   * IN (...)} filter every search stage must apply (ADR-0008 §5). Halts the run when the scope is
   * empty; no later stage may widen it.
   *
   * <p><b>Not switchable.</b> A run without the permission filter is a bypass, not a measurable
   * variant; {@link RetrievalPipeline} rejects such a configuration at construction time.
   */
  SEARCH_SCOPE,

  /**
   * Carries the caller-supplied core-field filter into the run - the Dokumentart set and the
   * Datum/Stand window (metadata-schema.md Wirkstelle 1) - in the vector-path form and the domain
   * form the lexical path translates to SQL. Both search stages AND it to the permission filter
   * from {@link #SEARCH_SCOPE} inside the query, before ranking, never on a result, so the filter
   * narrows the readable set and can never widen it. A document without a value for a filtered
   * field is kept ("Leerwerte schließen nicht aus").
   *
   * <p>Switched off, the searches run unfiltered whatever the caller asked for; the protocol says
   * so.
   */
  METADATA_FILTER,

  /**
   * Produces the search queries the search stages run, one each: 1 to {@link
   * QueryProperties#maxSubQueries} sub-queries from {@link QueryDecompositionService#decompose}, or
   * the single-query fallback whenever decomposition is off, fails, or returns nothing.
   *
   * <p>Note the two distinct "off" notions: {@link QueryProperties#queryDecompositionEnabled}
   * {@code = false} keeps this stage in the chain and yields the fallback query, while switching
   * the stage off removes the query-building step entirely - the search stages then run the bare
   * question, without the conversation-history prefix the fallback adds.
   */
  SUB_QUERY_DECOMPOSITION,

  /**
   * One {@code VectorStore#similaritySearch} per search query, each with the identical filter from
   * {@link #SEARCH_SCOPE} and the identical {@link QueryProperties#similarityThreshold}, yielding
   * {@link QueryProperties#fetchK} candidates per query. One of the two stages that add candidates
   * the pipeline did not already hold; every later stage is confined to what the two produced.
   *
   * <p>Switched off, the pipeline retrieves through the lexical path alone - the {@code
   * lexical-only} variant, and nothing at all if that path is switched off too.
   */
  VECTOR_SEARCH,

  /**
   * One PostgreSQL full-text query per search query, each with the identical filter from {@link
   * #SEARCH_SCOPE} and the identical {@link QueryProperties#fetchK}, over every library of the
   * scope - an incomplete full-text index narrows the list, never the scope (see {@link
   * FullTextIndexCompleteness}). The second stage that adds candidates; its lists enter {@link
   * #RANK_FUSION} next to the vector path's, one per search query.
   *
   * <p>Switched off, the pipeline retrieves through the vector path alone - the {@code vector-only}
   * measurement variant, which {@link QueryProperties#fullTextSearchEnabled()} expresses without
   * removing the stage from the chain.
   */
  FULL_TEXT_SEARCH,

  /**
   * Narrows each candidate list to {@link RetrievalContext#candidateBudget()} via {@code
   * MmrSelector} - {@link QueryProperties#topK} unless reranking runs, the wider rerank candidate
   * window if it does - trading relevance against redundancy at {@link QueryProperties#mmrLambda}
   * (at the shipped default {@code 1.0} this is plain top-k by relevance). Switched off, every list
   * stays at its full {@link QueryProperties#fetchK} length and the budget is enforced by {@link
   * #RANK_FUSION} alone.
   */
  MMR_SELECTION,

  /**
   * Merges every candidate list into one by rank via {@code ReciprocalRankFusion} and caps it at
   * {@link RetrievalContext#candidateBudget()}. Switched off, the lists are collapsed by ordered
   * concatenation deduplicated by chunk id and the top-k cap does not apply (see {@link
   * RetrievalState#selection}).
   */
  RANK_FUSION,

  /**
   * Re-scores the fused candidate window with the rerank model role ({@code
   * io.opaa.llm.RerankModelRole}) and cuts it back to {@link QueryProperties#topK}. Runs after
   * {@link #RANK_FUSION} and before {@link #DOCUMENT_COMPLETION}: completion adds sibling chunks of
   * already selected documents and must therefore work on the final ranking.
   *
   * <p>Off in the shipped configuration ({@code OPAA_RERANK_ENABLED}), and off it is the identity.
   * Switched on but unusable (role unbound, endpoint silent, call failed), the stage still restores
   * the {@code top-k} cap and records that it could not rerank, so a broken endpoint costs the
   * ordering, never the query. Taking it out through {@link RetrievalPipelineProperties} also takes
   * the widened budget with it ({@link RetrievalContext#withoutReranking()}), so it is the identity
   * there too, whatever {@link QueryProperties#rerankCandidateCount} says.
   */
  RERANK,

  /**
   * Lets a document already represented in the selection contribute up to {@link
   * QueryProperties#maxChunksPerDocument} chunks, drawn only from the candidate pool the search
   * stages produced - both the vector and the lexical path. Switched off, the selection stays
   * exactly as fusion left it, the same behaviour {@code maxChunksPerDocument = 1} produces.
   */
  DOCUMENT_COMPLETION
}
