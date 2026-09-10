package io.opaa.query.retrieval.ranking;

import io.opaa.llm.RerankClient.ScoredCandidate;
import io.opaa.llm.RerankModelRole;
import io.opaa.query.QueryProperties;
import io.opaa.query.retrieval.CandidateList;
import io.opaa.query.retrieval.CandidateOutcome;
import io.opaa.query.retrieval.CandidateVerdict;
import io.opaa.query.retrieval.RerankAvailability;
import io.opaa.query.retrieval.RetrievalContext;
import io.opaa.query.retrieval.RetrievalListLabel;
import io.opaa.query.retrieval.RetrievalNote;
import io.opaa.query.retrieval.RetrievalStage;
import io.opaa.query.retrieval.RetrievalStageName;
import io.opaa.query.retrieval.RetrievalState;
import io.opaa.query.retrieval.StageExplanation;
import io.opaa.query.retrieval.StageOutcome;
import io.opaa.query.retrieval.StageStatus;
import io.opaa.query.retrieval.VerdictReason;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * The {@link RetrievalStageName#RERANK} stage (docs/handbuch/suche.md, Stufe 8): re-scores the
 * fused candidate window with the rerank model role and cuts it back to {@link
 * QueryProperties#topK}.
 *
 * <p>No path passes on more than {@code top-k} chunks: the fusion widens its budget only while
 * reranking is active, so every {@code identity(...)} path is already capped and only the path
 * where the endpoint scored nothing restores the cap itself. A chunk the reranker did not score
 * keeps its fused order behind every scored one.
 *
 * <p>A failure costs the order, never the answer - but not the order of a run configured without
 * reranking: the narrowing stages kept the widened window, so the surviving order is a third state.
 */
@Component
public class RerankStage implements RetrievalStage {

  private final RerankModelRole role;

  public RerankStage(RerankModelRole role) {
    this.role = role;
  }

  @Override
  public RetrievalStageName name() {
    return RetrievalStageName.RERANK;
  }

  @Override
  public StageOutcome apply(RetrievalContext context, RetrievalState state) {
    QueryProperties properties = context.queryProperties();
    List<Document> incoming = state.selection();
    int topK = properties.topK();

    if (properties.rerankCandidateCount() == 0) {
      return identity(
          state, StageStatus.DISABLED, RetrievalNote.RERANK_DISABLED_BY_CANDIDATE_COUNT.format());
    }
    if (context.rerankAvailability() == RerankAvailability.SWITCHED_OFF) {
      return identity(
          state, StageStatus.DISABLED, RetrievalNote.RERANK_DISABLED_BY_ROLE_SWITCH.format());
    }
    if (context.rerankAvailability() == RerankAvailability.NOT_USABLE) {
      return identity(state, StageStatus.UNAVAILABLE, RetrievalNote.RERANK_NOT_USABLE.format());
    }
    if (incoming.isEmpty()) {
      return new StageOutcome(
          state.withCandidateLists(
              List.of(new CandidateList(RetrievalListLabel.FUSED_LIST_LABEL, List.of()))),
          StageExplanation.executed(
              name(), 0, 0, List.of(), List.of(RetrievalNote.RERANK_NOTHING_TO_RERANK.format())));
    }

    List<Document> window =
        List.copyOf(
            incoming.subList(0, Math.min(incoming.size(), properties.rerankCandidateCount())));
    List<ScoredCandidate> scored =
        role.rerank(context.question(), window.stream().map(RerankStage::textOf).toList());
    if (scored.isEmpty()) {
      return cappedWithoutRerank(
          state,
          incoming.subList(0, Math.min(incoming.size(), topK)),
          StageStatus.UNAVAILABLE,
          RetrievalNote.RERANK_SCORED_NOTHING.format(topK));
    }

    List<RankedCandidate> reranked = new ArrayList<>(incoming.size());
    reranked.addAll(reorder(window, scored));
    // Candidates behind the window keep their fused position behind the reranked ones. They only
    // exist when the window is smaller than the incoming list, and dropping them would let a window
    // below top-k shrink the answer's context.
    for (int i = window.size(); i < incoming.size(); i++) {
      reranked.add(new RankedCandidate(incoming.get(i), null));
    }
    List<Document> selection =
        reranked.stream().limit(topK).map(RankedCandidate::document).toList();

    List<CandidateVerdict> verdicts = new ArrayList<>(incoming.size());
    for (int rank = 1; rank <= reranked.size(); rank++) {
      RankedCandidate candidate = reranked.get(rank - 1);
      boolean kept = rank <= topK;
      verdicts.add(
          CandidateVerdict.of(
              candidate.document(),
              kept ? CandidateOutcome.KEPT : CandidateOutcome.DROPPED,
              kept ? VerdictReason.WITHIN_BUDGET : VerdictReason.OUTSIDE_RERANK_BUDGET,
              RetrievalListLabel.FUSED_LIST_LABEL,
              rank,
              candidate.score()));
    }

    return new StageOutcome(
        state.withCandidateLists(
            List.of(new CandidateList(RetrievalListLabel.FUSED_LIST_LABEL, selection))),
        StageExplanation.executed(
            name(),
            incoming.size(),
            selection.size(),
            verdicts,
            List.of(
                RetrievalNote.RERANK_CANDIDATE_WINDOW.format(properties.rerankCandidateCount()),
                RetrievalNote.RERANK_SCORED_COUNT.format(scored.size(), window.size()),
                RetrievalNote.OVERALL_BUDGET_TOP_K.format(topK))));
  }

  /**
   * The stage did not run at all: the state travels on untouched, lists and labels included. None
   * of these paths widened the fusion budget - {@link RetrievalContext#candidateBudget()} only
   * widens it while reranking is active - so there is nothing to cap, and rewriting the lists into
   * a single fused one would misstate where the candidates came from ({@link RetrievalStageName}: a
   * stage that did not run is the identity).
   */
  private StageOutcome identity(RetrievalState state, StageStatus status, String note) {
    int candidates = state.candidateCount();
    return new StageOutcome(
        state, StageExplanation.notRun(name(), status, candidates, candidates, note));
  }

  /**
   * The stage ran but could not rerank: the fused order is kept and cut back to {@code top-k},
   * because the fusion budget was widened for a reranker that then scored nothing. Only this path
   * needs a single list - a truncation of a merged order has no other honest shape.
   */
  private StageOutcome cappedWithoutRerank(
      RetrievalState state, List<Document> kept, StageStatus status, String note) {
    int incoming = state.candidateCount();
    return new StageOutcome(
        state.withCandidateLists(
            List.of(new CandidateList(RetrievalListLabel.FUSED_LIST_LABEL, List.copyOf(kept)))),
        StageExplanation.notRun(name(), status, incoming, kept.size(), note));
  }

  /** One candidate in the reranked order, with the score it got - {@code null} if it got none. */
  private record RankedCandidate(Document document, Double score) {}

  /**
   * The reranked window: the scored candidates in the model's order, then every candidate the model
   * did not score, in the fused order it came in with.
   */
  private static List<RankedCandidate> reorder(
      List<Document> window, List<ScoredCandidate> scored) {
    List<RankedCandidate> reordered = new ArrayList<>(window.size());
    boolean[] taken = new boolean[window.size()];
    for (ScoredCandidate candidate : scored) {
      if (!taken[candidate.index()]) {
        taken[candidate.index()] = true;
        reordered.add(new RankedCandidate(window.get(candidate.index()), candidate.score()));
      }
    }
    for (int i = 0; i < window.size(); i++) {
      if (!taken[i]) {
        reordered.add(new RankedCandidate(window.get(i), null));
      }
    }
    return reordered;
  }

  /** The chunk text the model scores; never null, so a text-less chunk cannot break a request. */
  private static String textOf(Document document) {
    String text = document.getText();
    return text == null ? "" : text;
  }
}
