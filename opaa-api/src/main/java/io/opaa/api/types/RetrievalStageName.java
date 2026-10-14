package io.opaa.api.types;

/**
 * The named stages of the retrieval pipeline (docs/handbuch/suche.md Abschnitt 4,
 * docs/features/retrieval-algorithm.md). The constant order is documentation only - the order
 * stages actually run in is the order {@code RetrievalConfiguration#retrievalPipeline} registers
 * them in, which is the one place it is decided.
 *
 * <p>Every stage except {@link #SEARCH_SCOPE} can be switched off via {@code
 * RetrievalPipelineProperties}, and a switched-off stage is the identity: the pipeline then runs
 * exactly as it would without that stage in the chain, not as it would with the stage present but
 * neutralized. What "without this stage" means is documented on the constant itself.
 */
public enum RetrievalStageName {

  /**
   * Turns the caller-supplied, already permission-resolved search scope into the {@code library_id
   * IN (...)} filter every search stage must apply
   * (docs/features/spaces-and-assets.md#durchsetzung-zur-abfragezeit). Halts the run when the scope
   * is empty; no later stage may widen it.
   *
   * <p><b>Not switchable.</b> A run without the permission filter is a bypass, not a measurable
   * variant; {@code RetrievalPipeline} rejects such a configuration at construction time.
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
   * Produces the search queries the search stages run, one each: 1 to {@code
   * QueryProperties#maxSubQueries} sub-queries from {@code QueryDecompositionService#decompose}, or
   * the single-query fallback whenever decomposition is off, fails, or finds nothing to search for
   * in the message - the last case also tells the answer so.
   *
   * <p>Note the two distinct "off" notions: {@code QueryProperties#queryDecompositionEnabled}
   * {@code = false} keeps this stage in the chain and yields the fallback query, while switching
   * the stage off removes the query-building step entirely - the search stages then run the bare
   * question, without the conversation-history prefix the fallback adds.
   */
  SUB_QUERY_DECOMPOSITION,

  /**
   * One {@code VectorChunkSearch#similaritySearch} per search query, each with the identical filter
   * from {@link #SEARCH_SCOPE} and the identical {@code QueryProperties#similarityThreshold},
   * yielding up to {@code QueryProperties#fetchK} candidates per query. One of the two stages that
   * add candidates the pipeline did not already hold; every later stage is confined to what the two
   * produced.
   *
   * <p>Switched off, the pipeline retrieves through the lexical path alone - the {@code
   * lexical-only} variant, and nothing at all if that path is switched off too.
   */
  VECTOR_SEARCH,

  /**
   * One PostgreSQL full-text query per search query, each with the identical filter from {@link
   * #SEARCH_SCOPE} and the identical {@code QueryProperties#fetchK}, over every library of the
   * scope. The second stage that adds candidates; its lists enter {@link #RANK_FUSION} next to the
   * vector path's, one per search query.
   *
   * <p>Switched off, the pipeline retrieves through the vector path alone - the {@code vector-only}
   * measurement variant, which {@code QueryProperties#fullTextSearchEnabled()} expresses without
   * removing the stage from the chain.
   */
  FULL_TEXT_SEARCH,

  /**
   * Reorders each candidate list via {@code MmrSelector}, trading relevance against redundancy at
   * {@code QueryProperties#mmrLambda}, and never narrows it: every list reaches {@link
   * #RANK_FUSION} at its full {@code QueryProperties#fetchK} length, and the fusion alone enforces
   * the budget. At the shipped default {@code 1.0} the reordering would be the identity, so the
   * stage does not run and reports itself as {@code StageStatus#DISABLED}.
   */
  MMR_SELECTION,

  /**
   * Merges every candidate list into one via {@code CandidateFusion} and caps it at {@code
   * RetrievalContext#candidateBudget()}. Switched off, the lists are collapsed by ordered
   * concatenation deduplicated by chunk id and the top-k cap does not apply (see {@code
   * RetrievalState#selection}).
   */
  RANK_FUSION,

  /**
   * Re-scores the fused candidate window with the rerank model role ({@code
   * io.opaa.llm.RerankModelRole}) and cuts it back to {@code QueryProperties#topK}. Runs after
   * {@link #RANK_FUSION} and before {@link #DOCUMENT_COMPLETION}: completion adds sibling chunks of
   * already selected documents and must therefore work on the final ranking.
   *
   * <p>Off in the shipped configuration ({@code OPAA_RERANK_ENABLED}), and off it is the identity.
   * Switched on but unusable (role unbound, endpoint silent, call failed), the stage still restores
   * the {@code top-k} cap and records that it could not rerank, so a broken endpoint costs the
   * ordering, never the query. Taking it out through {@code RetrievalPipelineProperties} also takes
   * the widened budget with it ({@code RetrievalContext#withoutReranking()}), so it is the identity
   * there too, whatever {@code QueryProperties#rerankCandidateCount} says.
   */
  RERANK,

  /**
   * Shortens the selection by score, as {@code RelevanceCutoffProperties} says: a relative
   * threshold to the best score or the first large drop, never below a minimum and never above
   * {@code top-k}. Runs after {@link #RERANK} so it sees the final scores. In the shipped mode
   * {@code FIXED} it does not run.
   */
  RELEVANCE_CUTOFF,

  /**
   * Tops a document already represented in the selection up to {@code
   * QueryProperties#maxChunksPerDocument} chunks - a fill target, not a cap - drawn only from the
   * candidate pool the search stages produced, both the vector and the lexical path. Switched off,
   * the selection stays exactly as fusion left it, the same behaviour {@code maxChunksPerDocument =
   * 1} produces.
   */
  DOCUMENT_COMPLETION
}
