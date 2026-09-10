package io.opaa.query.retrieval.search;

import io.opaa.query.retrieval.CandidateOutcome;
import io.opaa.query.retrieval.CandidateVerdict;
import io.opaa.query.retrieval.RetrievalContext;
import io.opaa.query.retrieval.RetrievalState;
import io.opaa.query.retrieval.VerdictReason;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;

/**
 * What the vector and the lexical search stage do identically: which queries they search, how they
 * record having derived those queries themselves, and how a retrieved candidate enters the
 * explanation protocol.
 */
final class SearchStageSupport {

  private SearchStageSupport() {}

  /**
   * The queries a search stage runs: the state's, or the bare question when {@code
   * SUB_QUERY_DECOMPOSITION} left none - a search stage never searches nothing.
   */
  static List<String> searchQueriesOrQuestion(RetrievalContext context, RetrievalState state) {
    return state.searchQueries().isEmpty() ? List.of(context.question()) : state.searchQueries();
  }

  /**
   * Records the queries a stage derived itself, so the run reports what it searched for. A state
   * that already carries queries travels on unchanged - either search path may be the one that
   * derived them.
   */
  static RetrievalState withRecordedSearchQueries(
      RetrievalState state, List<String> searchQueries) {
    return state.searchQueries().isEmpty() ? state.withSearchQueries(searchQueries) : state;
  }

  /**
   * One {@link CandidateOutcome#ADDED} verdict per candidate, ranked in the order the search
   * returned them and carrying that search's score.
   */
  static List<CandidateVerdict> retrievalVerdicts(String label, List<Document> candidates) {
    List<CandidateVerdict> verdicts = new ArrayList<>(candidates.size());
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
    return verdicts;
  }
}
