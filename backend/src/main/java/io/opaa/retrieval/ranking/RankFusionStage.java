package io.opaa.retrieval.ranking;

import io.opaa.retrieval.CandidateList;
import io.opaa.retrieval.CandidateOutcome;
import io.opaa.retrieval.CandidateVerdict;
import io.opaa.retrieval.FusionMethod;
import io.opaa.retrieval.QueryProperties;
import io.opaa.retrieval.RetrievalContext;
import io.opaa.retrieval.RetrievalListLabel;
import io.opaa.retrieval.RetrievalNote;
import io.opaa.retrieval.RetrievalStage;
import io.opaa.retrieval.RetrievalStageName;
import io.opaa.retrieval.RetrievalState;
import io.opaa.retrieval.StageExplanation;
import io.opaa.retrieval.StageOutcome;
import io.opaa.retrieval.VerdictReason;
import io.opaa.retrieval.ranking.CandidateFusion.FusedCandidate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * The {@link RetrievalStageName#RANK_FUSION} stage: merges every candidate list - two per search
 * query, the vector path's and the lexical path's - into one by {@link CandidateFusion} as {@link
 * QueryProperties.Fusion} says, and caps it at {@link RetrievalContext#candidateBudget()}.
 *
 * <p>Runs for a single list too, where it is provably the identity: within one list every rank is
 * distinct, so the fused scores are strictly decreasing in the list's own order. Deduplicates by
 * chunk id, never by score - a chunk two lists found independently is one candidate with two
 * contributions, and every verdict names them.
 *
 * <p>The budget is the context's, not {@link QueryProperties#topK} directly: with reranking active
 * fusion keeps the wider rerank candidate window and {@link RerankStage} restores the cap.
 *
 * <p>The top candidates of every list ({@link QueryProperties.Fusion#guaranteedPerList()}) are
 * taken whatever their fused rank, by {@link GuaranteedSelection}; the selection keeps the fused
 * order. A guaranteed candidate beyond the budget is reported as {@link
 * VerdictReason#GUARANTEED_LIST_TOP}, the candidate it displaced as {@link
 * VerdictReason#DISPLACED_BY_GUARANTEED_LIST_TOP}.
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
    QueryProperties properties = context.queryProperties();
    QueryProperties.Fusion fusion = properties.fusion();
    List<CandidateList> lists = state.candidateLists();
    List<FusedCandidate> fused =
        CandidateFusion.fuse(lists, fusion, properties.similarityThreshold());

    boolean[] taken = GuaranteedSelection.select(fused, FusedCandidate::guaranteed, budget);
    List<Document> selection = new ArrayList<>(Math.min(budget, fused.size()));
    Map<String, Double> scores = new HashMap<>();
    Set<String> guaranteedInSelection = new LinkedHashSet<>();
    long guaranteedCandidates = fused.stream().filter(FusedCandidate::guaranteed).count();
    int beyondBudget = 0;
    List<CandidateVerdict> verdicts = new ArrayList<>(fused.size());
    for (int i = 0; i < fused.size(); i++) {
      FusedCandidate candidate = fused.get(i);
      VerdictReason reason;
      int rank;
      if (taken[i]) {
        selection.add(candidate.document());
        scores.put(candidate.document().getId(), candidate.fusedScore());
        if (candidate.guaranteed()) {
          guaranteedInSelection.add(candidate.document().getId());
        }
        rank = selection.size();
        if (i < budget) {
          reason = VerdictReason.WITHIN_BUDGET;
        } else {
          reason = VerdictReason.GUARANTEED_LIST_TOP;
          beyondBudget++;
        }
      } else {
        rank = i + 1;
        reason =
            i < budget
                ? VerdictReason.DISPLACED_BY_GUARANTEED_LIST_TOP
                : VerdictReason.OUTSIDE_FUSION_BUDGET;
      }
      verdicts.add(
          CandidateVerdict.fused(
              candidate.document(),
              taken[i] ? CandidateOutcome.KEPT : CandidateOutcome.DROPPED,
              reason,
              candidate.subQuery() > 0
                  ? RetrievalListLabel.FUSED_SUB_QUERY.format(candidate.subQuery())
                  : RetrievalListLabel.FUSED_LIST_LABEL,
              rank,
              candidate.fusedScore(),
              candidate.contributions()));
    }

    int incoming = state.candidateCount();
    long subQueries =
        lists.stream().mapToInt(CandidateList::subQuery).filter(q -> q > 0).distinct().count();
    List<String> notes = new ArrayList<>();
    notes.add(
        fusion.method() == FusionMethod.SCORE
            ? RetrievalNote.SCORE_FUSION_LISTS.format(
                lists.size(),
                String.valueOf(fusion.vectorWeight()),
                String.valueOf(properties.similarityThreshold()))
            : RetrievalNote.RANK_FUSION_LISTS.format(lists.size()));
    if (subQueries > 1) {
      notes.add(
          fusion.subQueryQuota()
              ? RetrievalNote.FUSION_SUB_QUERY_QUOTA.format(subQueries)
              : RetrievalNote.FUSION_SUB_QUERIES_POOLED.format(subQueries));
    }
    if (fusion.guaranteedPerList() > 0) {
      notes.add(
          RetrievalNote.FUSION_GUARANTEED_PER_LIST.format(
              fusion.guaranteedPerList(), guaranteedCandidates, beyondBudget));
    }
    notes.add(
        context.rerankActive()
            ? RetrievalNote.BUDGET_WIDENED_TO_RERANK_WINDOW.format(budget)
            : RetrievalNote.OVERALL_BUDGET_TOP_K.format(budget));
    notes.add(RetrievalNote.DEDUPLICATED_BY_CHUNK_ID.format(incoming, fused.size()));
    return new StageOutcome(
        state
            .withCandidateLists(
                List.of(new CandidateList(RetrievalListLabel.FUSED_LIST_LABEL, selection)))
            .withSelectionScores(scores)
            .withGuaranteedChunkIds(guaranteedInSelection),
        StageExplanation.executed(name(), incoming, selection.size(), verdicts, notes));
  }
}
