package io.opaa.query;

/**
 * The named stages of the retrieval pipeline (docs/features/hybrid-retrieval.md, Arbeitspaket 1).
 * The constant order is documentation only - the order stages actually run in is the order {@code
 * QueryConfiguration#retrievalPipeline} registers them in, which is the one place it is decided.
 *
 * <p>Each stage can be switched off individually via {@link RetrievalPipelineProperties}, and a
 * switched-off stage is the identity: the pipeline then runs exactly as it would without that stage
 * in the chain - not as it would with the stage present but neutralized. That distinction is the
 * point of the switch: it lets a benchmark measure a stage's contribution rather than the
 * difference between two code paths. What "without this stage" means for each one is documented on
 * the constant itself.
 *
 * <p>{@link #SEARCH_SCOPE} is the one stage that cannot be switched off - see its own
 * documentation.
 */
public enum RetrievalStageName {

  /**
   * Turns the caller-supplied, already permission-resolved search scope into the {@code library_id
   * IN (...)} filter every search stage must apply (ADR-0008 §5,
   * docs/features/spaces-and-assets.md#durchsetzung-zur-abfragezeit). Halts the run when the scope
   * is empty: there is nothing to search, and no later stage may widen it.
   *
   * <p><b>Not switchable.</b> "Without this stage" would mean searching without a permission
   * filter, which is not a measurable variant but a permission bypass. {@link RetrievalPipeline}
   * rejects a configuration that names it at construction time rather than at query time.
   */
  SEARCH_SCOPE,

  /**
   * Produces the search queries the search stages run, one each: 1 to {@link
   * QueryProperties#maxSubQueries} sub-queries from {@link QueryDecompositionService#decompose}
   * (#923), or the single-query fallback whenever decomposition is off, fails, or returns nothing.
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
   * <p>Switched off, the pipeline retrieves through the lexical path alone (#1049) - the {@code
   * lexical-only} variant, and nothing at all if that path is switched off too.
   */
  VECTOR_SEARCH,

  /**
   * One PostgreSQL full-text query per search query, each with the identical filter from {@link
   * #SEARCH_SCOPE} and the identical {@link QueryProperties#fetchK}, over the libraries whose
   * full-text backfill has finished (docs/features/hybrid-retrieval.md, Arbeitspaket 2).
   *
   * <p>Its lists are inputs of {@link #RANK_FUSION} (#1049), one per search query, next to the
   * vector path's. Switched off, the pipeline retrieves through the vector path alone - the {@code
   * vector-only} measurement variant, which {@link QueryProperties#fullTextSearchEnabled()}
   * expresses without removing the stage from the chain.
   */
  FULL_TEXT_SEARCH,

  /**
   * Narrows each candidate list to {@link RetrievalContext#candidateBudget()} via {@link
   * MmrSelector} - {@link QueryProperties#topK} unless reranking runs, the wider rerank candidate
   * window if it does - trading
   * relevance against redundancy at {@link QueryProperties#mmrLambda} (at the shipped default
   * {@code 1.0} this is plain top-k by relevance). Switched off, every list stays at its full
   * {@link QueryProperties#fetchK} length and the budget is enforced by {@link #RANK_FUSION} alone.
   */
  MMR_SELECTION,

  /**
   * Merges every candidate list into one by rank via {@link ReciprocalRankFusion} and caps it at
   * {@link RetrievalContext#candidateBudget()}. Switched off, the lists are collapsed by ordered concatenation
   * deduplicated by chunk id and the top-k cap does not apply (see {@link
   * RetrievalState#selection}).
   */
  RANK_FUSION,

  /**
   * Re-scores the fused candidate window with the rerank model role ({@code
   * io.opaa.llm.RerankModelRole}) and cuts it back to {@link QueryProperties#topK}
   * (docs/features/hybrid-retrieval.md, Arbeitspaket 4). Runs after {@link #RANK_FUSION} and before
   * {@link #DOCUMENT_COMPLETION}: completion adds sibling chunks of already selected documents and
   * must therefore work on the final ranking, not on one the reranker would resort.
   *
   * <p>Reranking is off in the shipped configuration ({@code OPAA_RERANK_ENABLED}), and off it is
   * the identity - fusion then keeps {@code top-k} as before and this stage passes its input
   * through. Switched on but unusable (role unbound, endpoint silent, call failed), the stage still
   * restores the {@code top-k} cap and records that it could not rerank, so a broken endpoint costs
   * the ordering, never the query.
   *
   * <p>Switching the stage off through {@link RetrievalPipelineProperties} while reranking is
   * enabled leaves fusion's widened budget uncapped and hands on up to {@link
   * QueryProperties#rerankCandidateCount} chunks - a benchmark variant, never a shipped
   * configuration, the same way a switched-off {@link #RANK_FUSION} loses its own cap.
   */
  RERANK,

  /**
   * Lets a document already represented in the selection contribute up to {@link
   * QueryProperties#maxChunksPerDocument} chunks, drawn only from the candidate pool {@link
   * #VECTOR_SEARCH} produced (#932/#935). Switched off, the selection stays exactly as fusion left
   * it - the same behaviour {@code maxChunksPerDocument = 1} produces.
   */
  DOCUMENT_COMPLETION
}
