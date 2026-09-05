package io.opaa.query;

import java.util.Locale;

/**
 * Every note template a retrieval stage can put into {@link StageExplanation#notes()}, closed over
 * this enum instead of scattered string literals in the stages. Notes stay a free technical field
 * by contract (docs/features/retrieval-algorithm.md) - this enum does not turn them into an API
 * vocabulary, it only makes the set of sentences the backend can produce enumerable, so {@code
 * RetrievalNoteTest} can hold it against the German translation inventory in {@code
 * frontend/src/utils/retrievalProtocolText.test.ts} and fail loudly when the two drift apart.
 */
enum RetrievalNote {
  SEARCH_SCOPE_EMPTY("empty search scope: nothing readable in scope, retrieval halted"),
  SEARCH_SCOPE("search scope: %d %s"),
  SEARCH_SCOPE_PERMISSION_FILTER(
      "permission filter applied inside every search of this run, never afterwards"),

  METADATA_FILTER_NONE("no metadata filter set: every candidate of the search scope qualifies"),
  METADATA_FILTER_DOCUMENT_TYPES("metadata filter: document type in [%s]"),
  METADATA_FILTER_DATE_WINDOW(
      "metadata filter: document date from %s to %s, a value counting for the whole span its"
          + " precision leaves open"),
  METADATA_FILTER_LIBRARY_FIELD("metadata filter: library field %s of library %s %s"),
  METADATA_FILTER_FORMAT_FIELD("metadata filter: format field %s in [%s]"),
  METADATA_FILTER_SUBORDINATE(
      "metadata filter applied inside both search paths, AND-ed to the permission filter; a"
          + " document without a value for a filtered field is kept"),
  METADATA_FILTER_NO_VALUE_CANDIDATES(
      "metadata filter applied inside the query: %d of %d candidate(s) kept without a value for"
          + " a filtered field"),

  DECOMPOSITION_PRODUCED("decomposition produced %d %s"),
  DECOMPOSITION_FAILED(
      "decomposition returned nothing (failed or unparsable): single-query fallback"),
  DECOMPOSITION_DISABLED("decomposition switched off by configuration: single-query fallback"),
  SEARCH_QUERY("search query: %s"),

  VECTOR_SEARCH_LISTS("vector search, %d list(s)"),
  FETCH_K("fetch-k %d per list"),
  SIMILARITY_THRESHOLD("similarity threshold %s, applied in-query"),

  LEXICAL_PATH_DISABLED("lexical search path switched off (%s)"),
  LEXICAL_SEARCH_FAILED("lexical search failed for %s: %s"),
  FULL_TEXT_SEARCH_LISTS("full-text search, %d list(s)"),
  FULL_TEXT_PERMISSION_FILTER(
      "permission filter applied inside the query: %d scoped libraries searched, %d of them with an"
          + " incomplete full-text index"),

  PER_LIST_BUDGET("per-list budget %d"),
  MMR_LAMBDA_INACTIVE("mmr-lambda %s (diversity term inactive: plain top-k by relevance)"),
  MMR_LAMBDA_ACTIVE(
      "mmr-lambda %s (diversity term active, cosine similarity of real chunk embeddings)"),

  RANK_FUSION_LISTS("reciprocal rank fusion over %d list(s)"),
  BUDGET_WIDENED_TO_RERANK_WINDOW("budget widened to the rerank candidate window %d"),
  OVERALL_BUDGET_TOP_K("overall budget top-k %d"),
  DEDUPLICATED_BY_CHUNK_ID(
      "deduplicated by chunk id: %d list entries became %d distinct candidates"),

  RERANK_DISABLED_BY_CANDIDATE_COUNT(
      "reranking switched off through opaa.query.rerank-candidate-count=0"),
  RERANK_DISABLED_BY_ROLE_SWITCH(
      "reranking switched off through the rerank model role's own switch (opaa.rerank.enabled /"
          + " OPAA_RERANK_ENABLED)"),
  RERANK_NOT_USABLE(
      "the rerank model role is switched on but was not usable when this run started - no"
          + " endpoint or model is configured for it, or its endpoint did not answer; the role's"
          + " own state says which (RerankRoleStatusProvider#currentStatus)"),
  RERANK_NOTHING_TO_RERANK("no candidate reached this stage; there was nothing to rerank"),
  RERANK_SCORED_NOTHING(
      "the rerank model role scored nothing; the fused order was kept and capped at top-k %d"),
  RERANK_CANDIDATE_WINDOW("rerank candidate window %d"),
  RERANK_SCORED_COUNT("%d of %d candidate(s) scored by the rerank model"),

  MAX_CHUNKS_PER_DOCUMENT("max-chunks-per-document %d"),
  SIBLINGS_COMPLETED("%d sibling chunk(s) completed from the candidate pool");

  private final String template;

  RetrievalNote(String template) {
    this.template = template;
  }

  /** The raw {@link String#format} template, for the test that pins the set of notes down. */
  String template() {
    return template;
  }

  /**
   * The note text with {@code args} filled in, in {@link Locale#ROOT} so no note is
   * locale-dependent.
   */
  String format(Object... args) {
    return String.format(Locale.ROOT, template, args);
  }
}
