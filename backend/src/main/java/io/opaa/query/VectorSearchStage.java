package io.opaa.query;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Component;

/**
 * Step 3 of docs/features/retrieval-algorithm.md as a pipeline stage: one {@code similaritySearch}
 * per search query, each with the identical permission filter from {@link SearchScopeStage} and the
 * identical {@link QueryProperties#similarityThreshold}, yielding one candidate list per query.
 *
 * <p>One of the two stages that add candidates the run did not already hold ({@link
 * FullTextSearchStage} is the other, #1049) - together they call {@link
 * RetrievalState#withSearchResults} and form the ceiling every later stage works within.
 *
 * <p>With no search queries in the state - which happens exactly when {@link
 * RetrievalStageName#SUB_QUERY_DECOMPOSITION} is switched off - the bare question is searched and
 * recorded as the run's search query, so a run never reports having searched nothing while it did.
 * Note the difference to that stage's own fallback, which prepends the conversation's first user
 * message: a switched-off stage is absent, not neutralized.
 */
@Component
class VectorSearchStage implements RetrievalStage {

  private final VectorStore vectorStore;

  VectorSearchStage(VectorStore vectorStore) {
    this.vectorStore = vectorStore;
  }

  @Override
  public RetrievalStageName name() {
    return RetrievalStageName.VECTOR_SEARCH;
  }

  @Override
  public StageOutcome apply(RetrievalContext context, RetrievalState state) {
    Filter.Expression filter = SearchScopeStage.requiredLibraryFilter(state);
    QueryProperties properties = context.queryProperties();
    List<String> searchQueries =
        state.searchQueries().isEmpty() ? List.of(context.question()) : state.searchQueries();

    List<CandidateList> lists = new ArrayList<>(searchQueries.size());
    List<CandidateVerdict> verdicts = new ArrayList<>();
    for (int i = 0; i < searchQueries.size(); i++) {
      String label = listLabel(i);
      List<Document> candidates =
          vectorStore.similaritySearch(
              SearchRequest.builder()
                  .query(searchQueries.get(i))
                  .topK(properties.fetchK())
                  .similarityThreshold(properties.similarityThreshold())
                  .filterExpression(filter)
                  .build());
      lists.add(new CandidateList(label, candidates));
      for (int rank = 1; rank <= candidates.size(); rank++) {
        Document candidate = candidates.get(rank - 1);
        verdicts.add(
            CandidateVerdict.of(
                candidate,
                CandidateOutcome.ADDED,
                VerdictReason.RETRIEVED_BY_SEARCH,
                label,
                rank,
                candidate.getScore()));
      }
    }

    // Records the queries actually searched when this stage derived them itself, so the run always
    // reports what it searched for - the state's search queries are otherwise empty exactly when
    // the decomposition stage is switched off.
    RetrievalState searched =
        state.searchQueries().isEmpty() ? state.withSearchQueries(searchQueries) : state;
    int retrieved = lists.stream().mapToInt(list -> list.documents().size()).sum();
    return new StageOutcome(
        searched.withSearchResults(lists),
        StageExplanation.executed(
            name(),
            0,
            retrieved,
            verdicts,
            List.of(
                "vector search, " + searchQueries.size() + " list(s)",
                "fetch-k " + properties.fetchK() + " per list",
                "similarity threshold "
                    + properties.similarityThreshold()
                    + ", applied in-query")));
  }

  static String listLabel(int searchQueryIndex) {
    return "vector search · sub-query " + (searchQueryIndex + 1);
  }
}
