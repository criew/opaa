package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the exact set of note and list-label templates the retrieval stages can produce. A new or
 * changed {@link RetrievalNote} or {@link RetrievalListLabel} constant makes this test fail until
 * the expected map below is updated - the point at which the failure message tells the developer to
 * also update the German translation in {@code frontend/src/utils/retrievalProtocolText.ts} and its
 * test's {@code BACKEND_NOTES}/{@code LIST_LABEL} inventory (#1160).
 */
class RetrievalNoteTest {

  private static final String TRANSLATION_REMINDER =
      "A retrieval note/list-label template changed. Update the German translation in "
          + "frontend/src/utils/retrievalProtocolText.ts and its BACKEND_NOTES inventory in "
          + "frontend/src/utils/retrievalProtocolText.test.ts, then update this expected map.";

  @Test
  void notesMatchTheExpectedTemplateInventory() {
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put(
        "SEARCH_SCOPE_EMPTY", "empty search scope: nothing readable in scope, retrieval halted");
    expected.put("SEARCH_SCOPE", "search scope: %d %s");
    expected.put(
        "SEARCH_SCOPE_PERMISSION_FILTER",
        "permission filter applied inside every search of this run, never afterwards");
    expected.put("DECOMPOSITION_PRODUCED", "decomposition produced %d %s");
    expected.put(
        "DECOMPOSITION_FAILED",
        "decomposition returned nothing (failed or unparsable): single-query fallback");
    expected.put(
        "DECOMPOSITION_DISABLED",
        "decomposition switched off by configuration: single-query fallback");
    expected.put("SEARCH_QUERY", "search query: %s");
    expected.put("VECTOR_SEARCH_LISTS", "vector search, %d list(s)");
    expected.put("FETCH_K", "fetch-k %d per list");
    expected.put("SIMILARITY_THRESHOLD", "similarity threshold %s, applied in-query");
    expected.put("LEXICAL_PATH_DISABLED", "lexical search path switched off (%s)");
    expected.put(
        "NO_FULL_TEXT_BACKFILL",
        "no library of the search scope has a completed full-text backfill");
    expected.put(
        "FULL_TEXT_BACKFILL_PENDING",
        "the lexical path stays out of the fusion until a library's backfill is complete");
    expected.put("LEXICAL_SEARCH_FAILED", "lexical search failed for %s: %s");
    expected.put("FULL_TEXT_SEARCH_LISTS", "full-text search, %d list(s)");
    expected.put(
        "FULL_TEXT_PERMISSION_FILTER",
        "permission filter applied inside the query: %d of %d scoped libraries searched, the rest"
            + " awaiting their backfill");
    expected.put("PER_LIST_BUDGET", "per-list budget %d");
    expected.put(
        "MMR_LAMBDA_INACTIVE", "mmr-lambda %s (diversity term inactive: plain top-k by relevance)");
    expected.put(
        "MMR_LAMBDA_ACTIVE",
        "mmr-lambda %s (diversity term active, cosine similarity of real chunk embeddings)");
    expected.put("RANK_FUSION_LISTS", "reciprocal rank fusion over %d list(s)");
    expected.put(
        "BUDGET_WIDENED_TO_RERANK_WINDOW", "budget widened to the rerank candidate window %d");
    expected.put("OVERALL_BUDGET_TOP_K", "overall budget top-k %d");
    expected.put(
        "DEDUPLICATED_BY_CHUNK_ID",
        "deduplicated by chunk id: %d list entries became %d distinct candidates");
    expected.put(
        "RERANK_DISABLED_BY_CANDIDATE_COUNT",
        "reranking switched off through opaa.query.rerank-candidate-count=0");
    expected.put(
        "RERANK_DISABLED_BY_ROLE_SWITCH",
        "reranking switched off through the rerank model role's own switch (opaa.rerank.enabled /"
            + " OPAA_RERANK_ENABLED)");
    expected.put(
        "RERANK_NOT_USABLE",
        "the rerank model role is switched on but was not usable when this run started - no"
            + " endpoint or model is configured for it, or its endpoint did not answer; the role's"
            + " own state says which (RerankRoleStatusProvider#currentStatus)");
    expected.put(
        "RERANK_NOTHING_TO_RERANK", "no candidate reached this stage; there was nothing to rerank");
    expected.put(
        "RERANK_SCORED_NOTHING",
        "the rerank model role scored nothing; the fused order was kept and capped at top-k %d");
    expected.put("RERANK_CANDIDATE_WINDOW", "rerank candidate window %d");
    expected.put("RERANK_SCORED_COUNT", "%d of %d candidate(s) scored by the rerank model");
    expected.put("MAX_CHUNKS_PER_DOCUMENT", "max-chunks-per-document %d");
    expected.put("SIBLINGS_COMPLETED", "%d sibling chunk(s) completed from the candidate pool");

    Map<String, String> actual = new LinkedHashMap<>();
    for (RetrievalNote note : RetrievalNote.values()) {
      actual.put(note.name(), note.template());
    }

    assertThat(actual).as(TRANSLATION_REMINDER).isEqualTo(expected);
  }

  @Test
  void listLabelsMatchTheExpectedTemplateInventory() {
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("VECTOR_SEARCH", "vector search · sub-query %d");
    expected.put("FULL_TEXT_SEARCH", "full-text search · sub-query %d");
    expected.put("FUSED", "fused (RRF)");

    Map<String, String> actual = new LinkedHashMap<>();
    for (RetrievalListLabel label : RetrievalListLabel.values()) {
      actual.put(label.name(), label.template());
    }

    assertThat(actual).as(TRANSLATION_REMINDER).isEqualTo(expected);
  }
}
