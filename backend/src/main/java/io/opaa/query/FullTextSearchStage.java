package io.opaa.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * The {@link RetrievalStageName#FULL_TEXT_SEARCH} stage (docs/handbuch/suche.md, Stufe 5): one
 * PostgreSQL full-text query per search query, each with the identical permission filter the vector
 * path applies and the identical {@link QueryProperties#fetchK}, yielding one labelled candidate
 * list per search query. Its lists enter {@link RankFusionStage} next to the vector path's; a chunk
 * both paths found is one candidate with two contributions, deduplicated by chunk id and never by
 * score, because a cosine similarity and a {@code ts_rank} are not comparable quantities.
 *
 * <p>{@link QueryProperties#fullTextSearchEnabled()} is the only gate, and it narrows: every
 * library of the search scope is searched otherwise, even one whose full-text index is incomplete.
 * {@link FullTextIndexCompleteness} reports the affected libraries in this stage's notes; a
 * re-index repairs them (ADR-0028).
 *
 * <p>A failure degrades the path, never the answer: it is logged and recorded in the protocol, the
 * remaining lists continue into the fusion, and the fallback is an empty list rather than an
 * unfiltered one - no failure mode of this stage returns a chunk outside the search scope.
 */
@Component
class FullTextSearchStage implements RetrievalStage {

  private static final Logger log = LoggerFactory.getLogger(FullTextSearchStage.class);

  private final FullTextChunkSearch fullTextChunkSearch;
  private final FullTextIndexCompleteness indexCompleteness;

  FullTextSearchStage(
      FullTextChunkSearch fullTextChunkSearch, FullTextIndexCompleteness indexCompleteness) {
    this.fullTextChunkSearch = fullTextChunkSearch;
    this.indexCompleteness = indexCompleteness;
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
    SearchScopeStage.requiredLibraryFilter(state);

    int inFlight = state.candidateLists().stream().mapToInt(list -> list.documents().size()).sum();

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

    List<String> searchQueries =
        state.searchQueries().isEmpty() ? List.of(context.question()) : state.searchQueries();
    List<CandidateList> lists = new ArrayList<>(searchQueries.size());
    List<CandidateVerdict> verdicts = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    for (int i = 0; i < searchQueries.size(); i++) {
      String label = listLabel(i);
      List<Document> candidates;
      try {
        candidates =
            fullTextChunkSearch.search(
                searchQueries.get(i),
                searchScope,
                state.metadataFilter(),
                state.metadataFilterVocabularyCodes(),
                context.queryProperties().fetchK());
      } catch (RuntimeException e) {
        log.warn(
            "Lexical search path failed for sub-query {} - retrieval continues without its"
                + " candidates",
            i + 1,
            e);
        notes.add(RetrievalNote.LEXICAL_SEARCH_FAILED.format(label, e.getClass().getSimpleName()));
        continue;
      }
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

    int retrieved = lists.stream().mapToInt(list -> list.documents().size()).sum();
    notes.add(0, RetrievalNote.FULL_TEXT_SEARCH_LISTS.format(lists.size()));
    notes.add(1, RetrievalNote.FETCH_K.format(context.queryProperties().fetchK()));
    notes.add(
        2,
        RetrievalNote.FULL_TEXT_PERMISSION_FILTER.format(
            searchScope.size(), indexCompleteness.incompleteLibraryCount(searchScope)));
    if (!state.metadataFilter().isEmpty()) {
      List<Document> all = lists.stream().flatMap(list -> list.documents().stream()).toList();
      notes.add(
          3,
          RetrievalNote.METADATA_FILTER_NO_VALUE_CANDIDATES.format(
              MetadataFilterExpressions.countKeptWithoutValue(state.metadataFilter(), all),
              all.size()));
    }
    // Records the queries actually searched when this stage derived them itself, so a run never
    // reports having searched nothing while it did - either path may be the one that runs.
    RetrievalState searched =
        state.searchQueries().isEmpty() ? state.withSearchQueries(searchQueries) : state;
    return new StageOutcome(
        searched.withSearchResults(lists),
        StageExplanation.executed(name(), inFlight, inFlight + retrieved, verdicts, notes));
  }

  static String listLabel(int searchQueryIndex) {
    return RetrievalListLabel.FULL_TEXT_SEARCH.format(searchQueryIndex + 1);
  }
}
