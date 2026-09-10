package io.opaa.query.retrieval.ranking;

import io.opaa.query.QueryProperties;
import io.opaa.query.retrieval.CandidateList;
import io.opaa.query.retrieval.CandidateOutcome;
import io.opaa.query.retrieval.CandidateVerdict;
import io.opaa.query.retrieval.RetrievalContext;
import io.opaa.query.retrieval.RetrievalListLabel;
import io.opaa.query.retrieval.RetrievalNote;
import io.opaa.query.retrieval.RetrievalStage;
import io.opaa.query.retrieval.RetrievalStageName;
import io.opaa.query.retrieval.RetrievalState;
import io.opaa.query.retrieval.StageExplanation;
import io.opaa.query.retrieval.StageOutcome;
import io.opaa.query.retrieval.VerdictReason;
import io.opaa.query.retrieval.ranking.ReciprocalRankFusion.FusedCandidate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * The {@link RetrievalStageName#RANK_FUSION} stage: merges every candidate list - two per search
 * query, the vector path's and the lexical path's - into one by {@link ReciprocalRankFusion}
 * without weighting, and caps it at {@link RetrievalContext#candidateBudget()}.
 *
 * <p>Runs for a single list too, where it is provably the identity: within one list every rank is
 * distinct, so the fused scores are strictly decreasing in the list's own order and the cap is
 * already met by the per-list budget. Deduplicates by chunk id, never by score - a chunk two lists
 * found independently is one candidate with two contributions, and scores of different searches are
 * not comparable.
 *
 * <p>The budget is the context's, not {@link QueryProperties#topK} directly: with reranking active
 * fusion keeps the wider rerank candidate window and {@link RerankStage} restores the cap.
 */
@Component
public class RankFusionStage implements RetrievalStage {

  @Override
  public RetrievalStageName name() {
    return RetrievalStageName.RANK_FUSION;
  }

  @Override
  public StageOutcome apply(RetrievalContext context, RetrievalState state) {
    int budget = context.candidateBudget();
    List<List<Document>> rankedLists =
        state.candidateLists().stream().map(CandidateList::documents).toList();
    List<FusedCandidate> fused = ReciprocalRankFusion.fuseRanked(rankedLists);

    List<Document> selection = new ArrayList<>(Math.min(budget, fused.size()));
    List<CandidateVerdict> verdicts = new ArrayList<>(fused.size());
    for (int i = 0; i < fused.size(); i++) {
      FusedCandidate candidate = fused.get(i);
      boolean withinBudget = i < budget;
      if (withinBudget) {
        selection.add(candidate.document());
      }
      verdicts.add(
          CandidateVerdict.of(
              candidate.document(),
              withinBudget ? CandidateOutcome.KEPT : CandidateOutcome.DROPPED,
              withinBudget ? VerdictReason.WITHIN_BUDGET : VerdictReason.OUTSIDE_FUSION_BUDGET,
              RetrievalListLabel.FUSED_LIST_LABEL,
              i + 1,
              candidate.fusedScore()));
    }

    int incoming = state.candidateCount();
    return new StageOutcome(
        state.withCandidateLists(
            List.of(new CandidateList(RetrievalListLabel.FUSED_LIST_LABEL, selection))),
        StageExplanation.executed(
            name(),
            incoming,
            selection.size(),
            verdicts,
            List.of(
                RetrievalNote.RANK_FUSION_LISTS.format(rankedLists.size()),
                context.rerankActive()
                    ? RetrievalNote.BUDGET_WIDENED_TO_RERANK_WINDOW.format(budget)
                    : RetrievalNote.OVERALL_BUDGET_TOP_K.format(budget),
                RetrievalNote.DEDUPLICATED_BY_CHUNK_ID.format(incoming, fused.size()))));
  }
}
