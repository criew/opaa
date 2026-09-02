package io.opaa.query;

import io.opaa.llm.RerankClient.ScoredCandidate;
import io.opaa.llm.RerankModelRole;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * Re-scores the fused candidate window with the rerank model role and cuts it back to {@link
 * QueryProperties#topK} (docs/features/hybrid-retrieval.md, Arbeitspaket 4). Sits between {@link
 * RankFusionStage} and {@link DocumentCompletionStage}.
 *
 * <p><b>This stage restores the {@code top-k} cap on every path it can take</b> - reranked,
 * switched off, or unavailable. {@link RankFusionStage} widens its budget to {@link
 * QueryProperties#rerankCandidateCount} whenever reranking is active, so a path that passed on more
 * than {@code top-k} chunks would hand a multiple of the intended context to answer generation.
 *
 * <p>A chunk the reranker did not score keeps its fused order behind every scored one - whether it
 * sat behind the candidate window or the endpoint simply did not score it. An endpoint that answers
 * for part of the window must not make the rest disappear, and a window below {@code top-k} must
 * not shrink the answer's context.
 *
 * <p><b>A failure costs the order, never the answer - but not the order of a run without
 * reranking.</b> An endpoint that drops out mid-run leaves the fused order of the <i>widened</i>
 * window: {@link MmrSelectionStage} kept {@link QueryProperties#rerankCandidateCount} entries per
 * list instead of {@code top-k}, so ranks 9 to 50 of each list took part in the fusion and a chunk
 * found by both searches can outrank a rank-3 hit of a single one. The selection is therefore a
 * third state, distinct both from the reranked run and from the run configured without reranking.
 */
@Component
class RerankStage implements RetrievalStage {

  private final RerankModelRole role;

  RerankStage(RerankModelRole role) {
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
          state,
          StageStatus.DISABLED,
          "reranking switched off through opaa.query.rerank-candidate-count=0");
    }
    if (context.rerankAvailability() == RerankAvailability.SWITCHED_OFF) {
      return identity(
          state,
          StageStatus.DISABLED,
          "reranking switched off through the rerank model role's own switch "
              + "(opaa.rerank.enabled / OPAA_RERANK_ENABLED)");
    }
    if (context.rerankAvailability() == RerankAvailability.NOT_USABLE) {
      return identity(
          state,
          StageStatus.UNAVAILABLE,
          "the rerank model role is switched on but was not usable when this run started - no "
              + "endpoint or model is configured for it, or its endpoint did not answer; the "
              + "role's own state says which (RerankRoleStatusProvider#currentStatus)");
    }
    if (incoming.isEmpty()) {
      return new StageOutcome(
          state.withCandidateLists(
              List.of(new CandidateList(RankFusionStage.FUSED_LIST_LABEL, List.of()))),
          StageExplanation.executed(
              name(),
              0,
              0,
              List.of(),
              List.of("no candidate reached this stage; there was nothing to rerank")));
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
          "the rerank model role scored nothing; the fused order was kept and capped at top-k "
              + topK);
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
              RankFusionStage.FUSED_LIST_LABEL,
              rank,
              candidate.score()));
    }

    return new StageOutcome(
        state.withCandidateLists(
            List.of(new CandidateList(RankFusionStage.FUSED_LIST_LABEL, selection))),
        StageExplanation.executed(
            name(),
            incoming.size(),
            selection.size(),
            verdicts,
            List.of(
                "rerank candidate window " + properties.rerankCandidateCount(),
                scored.size() + " of " + window.size() + " candidate(s) scored by the rerank model",
                "overall budget top-k " + topK)));
  }

  /**
   * The stage did not run at all: the state travels on untouched, lists and labels included. None
   * of these paths widened the fusion budget - {@link RetrievalContext#candidateBudget()} only
   * widens it while reranking is active - so there is nothing to cap, and rewriting the lists into
   * a single fused one would misstate where the candidates came from ({@link RetrievalStageName}: a
   * stage that did not run is the identity).
   */
  private StageOutcome identity(RetrievalState state, StageStatus status, String note) {
    int candidates =
        state.candidateLists().stream().mapToInt(list -> list.documents().size()).sum();
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
    int incoming = state.candidateLists().stream().mapToInt(list -> list.documents().size()).sum();
    return new StageOutcome(
        state.withCandidateLists(
            List.of(new CandidateList(RankFusionStage.FUSED_LIST_LABEL, List.copyOf(kept)))),
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
