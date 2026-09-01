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
 * The lexical search path as a pipeline stage (docs/features/hybrid-retrieval.md, Arbeitspaket 2):
 * one PostgreSQL full-text query per search query, each with the identical permission filter the
 * vector path applies and the identical {@link QueryProperties#fetchK}, yielding one labelled
 * candidate list per search query.
 *
 * <p><b>Not yet an input of the fusion.</b> The lists this stage produces reach the explanation
 * protocol and stop there; {@link RetrievalState} is handed on untouched, so the selection this
 * pipeline returns is bit-identical to the one it returned without this stage. That is deliberate
 * and temporary: the fusion takes the lexical list as a further input in #1049, which is where the
 * change of behaviour - and the re-drawn benchmark baselines it requires - belongs. Until then the
 * path is fully built, fully permission-filtered and fully visible in the diagnosis, and it cannot
 * move a single measured number.
 *
 * <p><b>Two gates, both narrowing, never widening:</b>
 *
 * <ul>
 *   <li>{@link FullTextSearchProperties#enabled()} - the operator's switch.
 *   <li>{@link FullTextBackfillGate} - a library whose backfill has not finished is not searched. A
 *       half-filled full-text index returns hits and hides the rest, which is worse than returning
 *       nothing (docs/features/hybrid-retrieval.md, "Arbeitspaket 2a").
 * </ul>
 *
 * <p><b>A failure degrades the path, never the answer.</b> A missing or broken full-text column may
 * cost search quality and must not raise for the person asking (docs/features/hybrid-retrieval.md,
 * Arbeitspaket 3: "Der Volltextpfad hat dieselbe Ausfallsicherheit wie die Teilfragen-Zerlegung").
 * The failure is logged and recorded in the protocol as such - never swallowed into a silently
 * empty result that looks like "nothing matched". Note what the fallback is <em>not</em>: it is an
 * empty list, never an unfiltered one, so no failure mode of this stage can return a chunk outside
 * the search scope.
 */
@Component
class FullTextSearchStage implements RetrievalStage {

  private static final Logger log = LoggerFactory.getLogger(FullTextSearchStage.class);

  private final FullTextChunkSearch fullTextChunkSearch;
  private final FullTextBackfillGate backfillGate;
  private final FullTextSearchProperties properties;

  FullTextSearchStage(
      FullTextChunkSearch fullTextChunkSearch,
      FullTextBackfillGate backfillGate,
      FullTextSearchProperties properties) {
    this.fullTextChunkSearch = fullTextChunkSearch;
    this.backfillGate = backfillGate;
    this.properties = properties;
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

    // Incoming and outgoing are the same number throughout this stage, and deliberately so: it
    // passes the lists in flight on unchanged. Its own candidates are not part of that count until
    // #1049 hands them to the fusion - they are reported in the verdicts and in a note instead.
    int inFlight = state.candidateLists().stream().mapToInt(list -> list.documents().size()).sum();

    if (!properties.enabled()) {
      return new StageOutcome(
          state,
          StageExplanation.executed(
              name(),
              inFlight,
              inFlight,
              List.of(),
              List.of("lexical search path switched off (opaa.query.full-text-search.enabled)")));
    }

    Set<UUID> searchable = backfillGate.searchableLibraries(context.searchScope());
    if (searchable.isEmpty()) {
      return new StageOutcome(
          state,
          StageExplanation.executed(
              name(),
              inFlight,
              inFlight,
              List.of(),
              List.of(
                  "no library of the search scope has a completed full-text backfill",
                  "the lexical path stays out until a library's backfill is complete")));
    }

    List<String> searchQueries =
        state.searchQueries().isEmpty() ? List.of(context.question()) : state.searchQueries();
    List<CandidateVerdict> verdicts = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    int retrieved = 0;
    for (int i = 0; i < searchQueries.size(); i++) {
      String label = listLabel(i);
      List<Document> candidates;
      try {
        candidates =
            fullTextChunkSearch.search(
                searchQueries.get(i), searchable, context.queryProperties().fetchK());
      } catch (RuntimeException e) {
        log.warn(
            "Lexical search path failed for sub-query {} - retrieval continues without its"
                + " candidates",
            i + 1,
            e);
        notes.add("lexical search failed for " + label + ": " + e.getClass().getSimpleName());
        continue;
      }
      retrieved += candidates.size();
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

    notes.add(0, "full-text search, " + searchQueries.size() + " list(s)");
    notes.add(1, "fetch-k " + context.queryProperties().fetchK() + " per list");
    notes.add(
        2,
        "permission filter applied inside the query: "
            + searchable.size()
            + " of "
            + context.searchScope().size()
            + " scoped libraries searched, the rest awaiting their backfill");
    notes.add(
        3,
        retrieved
            + " lexical candidate(s) found and recorded here only - the fusion takes them as an"
            + " input in #1049, which is why they are not counted as this stage's output");
    return new StageOutcome(
        state, StageExplanation.executed(name(), inFlight, inFlight, verdicts, notes));
  }

  static String listLabel(int searchQueryIndex) {
    return "full-text search · sub-query " + (searchQueryIndex + 1);
  }
}
