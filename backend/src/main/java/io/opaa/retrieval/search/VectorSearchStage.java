package io.opaa.retrieval.search;

import io.opaa.indexing.chunk.EmbeddingIndexState;
import io.opaa.retrieval.CandidateList;
import io.opaa.retrieval.CandidateVerdict;
import io.opaa.retrieval.QueryProperties;
import io.opaa.retrieval.RetrievalContext;
import io.opaa.retrieval.RetrievalListLabel;
import io.opaa.retrieval.RetrievalNote;
import io.opaa.retrieval.RetrievalStage;
import io.opaa.retrieval.RetrievalStageName;
import io.opaa.retrieval.RetrievalState;
import io.opaa.retrieval.SearchPath;
import io.opaa.retrieval.StageExplanation;
import io.opaa.retrieval.StageOutcome;
import io.opaa.retrieval.StageStatus;
import io.opaa.retrieval.scope.MetadataFilterExpressions;
import io.opaa.retrieval.scope.SearchScopeStage;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Component;

/**
 * The {@link RetrievalStageName#VECTOR_SEARCH} stage: one {@link VectorChunkSearch} per search
 * query, each with the identical permission filter from {@link SearchScopeStage} and the identical
 * {@link QueryProperties#similarityThreshold}, yielding one candidate list per query. A list
 * shorter than fetch-k is named in the explanation protocol.
 *
 * <p>One of the two stages that add candidates the run did not already hold ({@link
 * FullTextSearchStage} is the other) - together they call {@link RetrievalState#withSearchResults}
 * and form the ceiling every later stage works within.
 *
 * <p>With no search queries in the state - which happens exactly when {@link
 * RetrievalStageName#SUB_QUERY_DECOMPOSITION} is switched off - the bare question is searched and
 * recorded as the run's search query, so a run never reports having searched nothing while it did.
 *
 * <p>While the vector index is stale ({@link EmbeddingIndexState}) the stage searches nothing and
 * reports itself {@link StageStatus#UNAVAILABLE}; the full-text path carries the run alone.
 */
@Component
public class VectorSearchStage implements RetrievalStage {

  private final VectorChunkSearch vectorChunkSearch;
  private final EmbeddingIndexState indexState;

  public VectorSearchStage(VectorChunkSearch vectorChunkSearch, EmbeddingIndexState indexState) {
    this.vectorChunkSearch = vectorChunkSearch;
    this.indexState = indexState;
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
    if (!indexState.isVectorSearchAvailable()) {
      // Question and chunks would be embedded by different models: no list beats a wrong one.
      int inFlight = state.candidateCount();
      return new StageOutcome(
          SearchStageSupport.withRecordedSearchQueries(state, searchQueries),
          StageExplanation.notRun(
              name(),
              StageStatus.UNAVAILABLE,
              inFlight,
              inFlight,
              RetrievalNote.VECTOR_SEARCH_SUSPENDED.format()));
    }

    List<CandidateList> lists = new ArrayList<>(searchQueries.size());
    List<CandidateVerdict> verdicts = new ArrayList<>();
    List<String> shortListNotes = new ArrayList<>();
    for (int i = 0; i < searchQueries.size(); i++) {
      String label = listLabel(i);
      List<Document> candidates =
          vectorChunkSearch.similaritySearch(
              SearchRequest.builder()
                  .query(searchQueries.get(i))
                  .topK(properties.fetchK())
                  .similarityThreshold(properties.similarityThreshold())
                  .filterExpression(filter)
                  .build());
      lists.add(new CandidateList(label, candidates, SearchPath.VECTOR, i + 1));
      verdicts.addAll(SearchStageSupport.retrievalVerdicts(label, candidates));
      if (candidates.size() < properties.fetchK()) {
        shortListNotes.add(
            RetrievalNote.VECTOR_SEARCH_SHORT_LIST.format(
                label, candidates.size(), properties.fetchK()));
      }
    }

    RetrievalState searched = SearchStageSupport.withRecordedSearchQueries(state, searchQueries);
    int retrieved = lists.stream().mapToInt(list -> list.documents().size()).sum();
    List<String> notes = new ArrayList<>();
    notes.add(RetrievalNote.VECTOR_SEARCH_LISTS.format(searchQueries.size()));
    notes.add(RetrievalNote.FETCH_K.format(properties.fetchK()));
    notes.add(RetrievalNote.SIMILARITY_THRESHOLD.format(properties.similarityThreshold()));
    notes.addAll(shortListNotes);
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
