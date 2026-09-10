package io.opaa.query.retrieval.search;

import io.opaa.query.QueryProperties;
import io.opaa.query.retrieval.CandidateList;
import io.opaa.query.retrieval.CandidateVerdict;
import io.opaa.query.retrieval.RetrievalContext;
import io.opaa.query.retrieval.RetrievalListLabel;
import io.opaa.query.retrieval.RetrievalNote;
import io.opaa.query.retrieval.RetrievalStage;
import io.opaa.query.retrieval.RetrievalStageName;
import io.opaa.query.retrieval.RetrievalState;
import io.opaa.query.retrieval.StageExplanation;
import io.opaa.query.retrieval.StageOutcome;
import io.opaa.query.retrieval.scope.MetadataFilterExpressions;
import io.opaa.query.retrieval.scope.SearchScopeStage;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Component;

/**
 * The {@link RetrievalStageName#VECTOR_SEARCH} stage: one {@code similaritySearch} per search
 * query, each with the identical permission filter from {@link SearchScopeStage} and the identical
 * {@link QueryProperties#similarityThreshold}, yielding one candidate list per query.
 *
 * <p>One of the two stages that add candidates the run did not already hold ({@link
 * FullTextSearchStage} is the other) - together they call {@link RetrievalState#withSearchResults}
 * and form the ceiling every later stage works within.
 *
 * <p>With no search queries in the state - which happens exactly when {@link
 * RetrievalStageName#SUB_QUERY_DECOMPOSITION} is switched off - the bare question is searched and
 * recorded as the run's search query, so a run never reports having searched nothing while it did.
 */
@Component
public class VectorSearchStage implements RetrievalStage {

  private final VectorStore vectorStore;

  public VectorSearchStage(VectorStore vectorStore) {
    this.vectorStore = vectorStore;
  }

  @Override
  public RetrievalStageName name() {
    return RetrievalStageName.VECTOR_SEARCH;
  }

  @Override
  public StageOutcome apply(RetrievalContext context, RetrievalState state) {
    // The permission filter is the outer condition; the metadata filter can only ever remove from
    // what it allows - see MetadataFilterExpressions#subordinateTo.
    Filter.Expression filter =
        MetadataFilterExpressions.subordinateTo(
            state.requiredLibraryFilter(), state.metadataFilterExpression());
    QueryProperties properties = context.queryProperties();
    List<String> searchQueries = SearchStageSupport.searchQueriesOrQuestion(context, state);

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
      verdicts.addAll(SearchStageSupport.retrievalVerdicts(label, candidates));
    }

    RetrievalState searched = SearchStageSupport.withRecordedSearchQueries(state, searchQueries);
    int retrieved = lists.stream().mapToInt(list -> list.documents().size()).sum();
    List<String> notes = new ArrayList<>();
    notes.add(RetrievalNote.VECTOR_SEARCH_LISTS.format(searchQueries.size()));
    notes.add(RetrievalNote.FETCH_K.format(properties.fetchK()));
    notes.add(RetrievalNote.SIMILARITY_THRESHOLD.format(properties.similarityThreshold()));
    if (!state.metadataFilter().isEmpty()) {
      List<Document> all = lists.stream().flatMap(list -> list.documents().stream()).toList();
      notes.add(
          RetrievalNote.METADATA_FILTER_NO_VALUE_CANDIDATES.format(
              MetadataFilterExpressions.countKeptWithoutValue(state.metadataFilter(), all),
              all.size()));
    }
    return new StageOutcome(
        searched.withSearchResults(lists),
        StageExplanation.executed(name(), 0, retrieved, verdicts, notes));
  }

  static String listLabel(int searchQueryIndex) {
    return RetrievalListLabel.VECTOR_SEARCH.format(searchQueryIndex + 1);
  }
}
