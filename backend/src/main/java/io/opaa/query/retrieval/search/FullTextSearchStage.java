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
import io.opaa.query.retrieval.ranking.RankFusionStage;
import io.opaa.query.retrieval.scope.MetadataFilterExpressions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * The {@link RetrievalStageName#FULL_TEXT_SEARCH} stage (docs/handbuch/suche.md, Stufe 5): one
 * PostgreSQL full-text query per search query, each with the identical permission filter the vector
 * path applies and the identical {@link QueryProperties#fetchK}, yielding one labelled candidate
 * list per search query. Its lists enter {@link RankFusionStage} next to the vector path's,
 * deduplicated by chunk id and never by score.
 *
 * <p>{@link QueryProperties#fullTextSearchEnabled()} is the only gate, and it narrows: every
 * library of the scope is searched otherwise.
 *
 * <p>A failed query fails the run, exactly as in the vector path: half a hybrid search is a worse
 * answer, not a valid one, and must not pass itself off as poor search quality.
 */
@Component
public class FullTextSearchStage implements RetrievalStage {

  private final FullTextChunkSearch fullTextChunkSearch;

  public FullTextSearchStage(FullTextChunkSearch fullTextChunkSearch) {
    this.fullTextChunkSearch = fullTextChunkSearch;
  }

  @Override
  public RetrievalStageName name() {
    return RetrievalStageName.FULL_TEXT_SEARCH;
  }

  @Override
  public StageOutcome apply(RetrievalContext context, RetrievalState state) {
    // Not read for the SQL - which needs the raw library ids - but demanded all the same: a search
    // stage running before SEARCH_SCOPE established the filter must fail loudly (ADR-0008 §5),
    // exactly as it does in the vector path.
    state.requiredLibraryFilter();

    int inFlight = state.candidateCount();

    if (!context.queryProperties().fullTextSearchEnabled()) {
      return new StageOutcome(
          state,
          StageExplanation.executed(
              name(),
              inFlight,
              inFlight,
              List.of(),
              List.of(
                  RetrievalNote.LEXICAL_PATH_DISABLED.format(
                      "opaa.query.full-text-search-enabled"))));
    }

    Set<UUID> searchScope = context.searchScope();

    List<String> searchQueries = SearchStageSupport.searchQueriesOrQuestion(context, state);
    List<CandidateList> lists = new ArrayList<>(searchQueries.size());
    List<CandidateVerdict> verdicts = new ArrayList<>();
    for (int i = 0; i < searchQueries.size(); i++) {
      String label = listLabel(i);
      List<Document> candidates =
          fullTextChunkSearch.search(
              searchQueries.get(i),
              searchScope,
              state.metadataFilter(),
              state.metadataFilterVocabularyCodes(),
              context.queryProperties().fetchK());
      lists.add(new CandidateList(label, candidates));
      verdicts.addAll(SearchStageSupport.retrievalVerdicts(label, candidates));
    }

    int retrieved = lists.stream().mapToInt(list -> list.documents().size()).sum();
    List<String> notes = new ArrayList<>();
    notes.add(RetrievalNote.FULL_TEXT_SEARCH_LISTS.format(lists.size()));
    notes.add(RetrievalNote.FETCH_K.format(context.queryProperties().fetchK()));
    if (!state.metadataFilter().isEmpty()) {
      List<Document> all = lists.stream().flatMap(list -> list.documents().stream()).toList();
      notes.add(
          RetrievalNote.METADATA_FILTER_NO_VALUE_CANDIDATES.format(
              MetadataFilterExpressions.countKeptWithoutValue(state.metadataFilter(), all),
              all.size()));
    }
    RetrievalState searched = SearchStageSupport.withRecordedSearchQueries(state, searchQueries);
    return new StageOutcome(
        searched.withSearchResults(lists),
        StageExplanation.executed(name(), inFlight, inFlight + retrieved, verdicts, notes));
  }

  static String listLabel(int searchQueryIndex) {
    return RetrievalListLabel.FULL_TEXT_SEARCH.format(searchQueryIndex + 1);
  }
}
