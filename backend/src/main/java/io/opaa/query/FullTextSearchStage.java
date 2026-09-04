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
 * The lexical search path as a pipeline stage (docs/features/hybrid-retrieval.md, Arbeitspaket
 * 2/3): one PostgreSQL full-text query per search query, each with the identical permission filter
 * the vector path applies and the identical {@link QueryProperties#fetchK}, yielding one labelled
 * candidate list per search query.
 *
 * <p><b>Its lists are inputs of the fusion</b> (#1049): they are handed on in the pipeline state
 * next to the vector path's, so {@link RankFusionStage} merges both paths of every search query by
 * rank. A chunk both paths found is one candidate with two contributions, never two candidates -
 * deduplication is by chunk id, never by score, because a cosine similarity and a {@code ts_rank}
 * are not comparable quantities (#912).
 *
 * <p><b>The second stage that adds candidates the run did not already hold</b>, and therefore the
 * second caller of {@link RetrievalState#withSearchResults}. Everything it adds passed the same
 * permission filter as the vector path's candidates, so the pool invariant that confines document
 * completion to permission-scoped chunks (#932) holds unchanged.
 *
 * <p><b>One gate, narrowing, never widening:</b> {@link QueryProperties#fullTextSearchEnabled()} -
 * the operator's switch, and the {@code vector-only} measurement variant. Every library of the
 * search scope is searched otherwise: a chunk's full-text row is written in the same transaction as
 * its vector row, so there is no state in which a scoped library is vectorized but not yet
 * full-text-indexed (#1270 removed the per-library completion gate that guarded that state).
 *
 * <p><b>A failure degrades the path, never the answer.</b> A missing or broken full-text column may
 * cost search quality and must not raise for the person asking (docs/features/hybrid-retrieval.md,
 * Arbeitspaket 3: "Der Volltextpfad hat dieselbe Ausfallsicherheit wie die Teilfragen-Zerlegung").
 * The failure is logged and recorded in the protocol as such - never swallowed into a silently
 * empty result that looks like "nothing matched" - and the fusion continues with the remaining
 * lists. Note what the fallback is <em>not</em>: it is an empty list, never an unfiltered one, so
 * no failure mode of this stage can return a chunk outside the search scope.
 */
@Component
class FullTextSearchStage implements RetrievalStage {

  private static final Logger log = LoggerFactory.getLogger(FullTextSearchStage.class);

  private final FullTextChunkSearch fullTextChunkSearch;

  FullTextSearchStage(FullTextChunkSearch fullTextChunkSearch) {
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
                searchQueries.get(i), searchScope, context.queryProperties().fetchK());
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
    notes.add(2, RetrievalNote.FULL_TEXT_PERMISSION_FILTER.format(searchScope.size()));
    // Records the queries actually searched when this stage derived them itself, so a run never
    // reports having searched nothing while it did - the vector path does the same, and either
    // path may be the one that runs.
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
