package io.opaa.query;

import io.opaa.query.ReciprocalRankFusion.FusedCandidate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * Step 5 of docs/features/retrieval-algorithm.md as a pipeline stage: merges every candidate list
 * into one by {@link ReciprocalRankFusion} and caps it at {@link QueryProperties#topK}.
 *
 * <p>Runs for a single list too, where it is provably the identity: within one list every rank is
 * distinct, so the fused scores are strictly decreasing in the list's own order and the cap is
 * already met by the per-list budget. That is deliberate - the pre-#923 single-query path used to
 * skip fusion, and reproducing that as a branch would be a second code path to keep in step with
 * this one for no gain (see docs/features/hybrid-retrieval.md, "Was das Refactoring ausdrücklich
 * nicht tut").
 *
 * <p>Deduplicates by chunk id, never by score: a chunk two lists found independently is one
 * candidate with two contributions, and scores from different searches are not comparable (#912).
 */
@Component
class RankFusionStage implements RetrievalStage {

  static final String FUSED_LIST_LABEL = "fused (RRF)";

  @Override
  public RetrievalStageName name() {
    return RetrievalStageName.RANK_FUSION;
  }

  @Override
  public StageOutcome apply(RetrievalContext context, RetrievalState state) {
    int budget = context.queryProperties().topK();
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
              FUSED_LIST_LABEL,
              candidate.fusedScore()));
    }

    int incoming = rankedLists.stream().mapToInt(List::size).sum();
    return new StageOutcome(
        state.withCandidateLists(List.of(new CandidateList(FUSED_LIST_LABEL, selection))),
        StageExplanation.executed(
            name(),
            incoming,
            selection.size(),
            verdicts,
            List.of(
                "reciprocal rank fusion over " + rankedLists.size() + " list(s)",
                "overall budget top-k " + budget,
                "deduplicated by chunk id: "
                    + incoming
                    + " list entries became "
                    + fused.size()
                    + " distinct candidates")));
  }
}
