package io.opaa.query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * The {@link RetrievalStageName#MMR_SELECTION} stage: narrows every candidate list to {@link
 * RetrievalContext#candidateBudget()} via {@link MmrSelector}, each list on its own - MMR runs
 * inside one search query's candidate pool, never across the pooled cross-topic result. It narrows
 * only, so every chunk it passes on stays permission-scoped by the search that produced it.
 *
 * <p>The chunk embeddings MMR needs are read once for the whole run over the pooled candidates. At
 * {@link QueryProperties#mmrLambda} {@code >= 1.0} the diversity term is multiplied by zero, so
 * {@link ChunkEmbeddingLookup} is skipped entirely - it could not affect the result.
 *
 * <p>Below {@code 1.0} the two paths' lists are treated unequally and knowingly so: the relevance
 * term is the candidate's own score - a cosine similarity in a vector list, a {@code ts_rank} an
 * order of magnitude smaller in a lexical one - while the diversity term is a cosine similarity in
 * both, so a lexical list is then ordered almost entirely by diversity.
 */
@Component
class MmrSelectionStage implements RetrievalStage {

  private final ChunkEmbeddingLookup chunkEmbeddingLookup;

  MmrSelectionStage(ChunkEmbeddingLookup chunkEmbeddingLookup) {
    this.chunkEmbeddingLookup = chunkEmbeddingLookup;
  }

  @Override
  public RetrievalStageName name() {
    return RetrievalStageName.MMR_SELECTION;
  }

  @Override
  public StageOutcome apply(RetrievalContext context, RetrievalState state) {
    QueryProperties properties = context.queryProperties();
    Map<String, float[]> embeddings = lookupEmbeddings(state.candidatePool(), properties);

    List<CandidateList> narrowed = new ArrayList<>(state.candidateLists().size());
    List<CandidateVerdict> verdicts = new ArrayList<>();
    int incoming = 0;
    for (CandidateList list : state.candidateLists()) {
      List<Document> selected =
          MmrSelector.select(
              list.documents(), context.candidateBudget(), properties.mmrLambda(), embeddings);
      narrowed.add(new CandidateList(list.label(), selected));
      incoming += list.documents().size();

      Map<String, Integer> rankInSelection = new HashMap<>();
      for (int i = 0; i < selected.size(); i++) {
        rankInSelection.put(selected.get(i).getId(), i + 1);
      }
      for (int incomingRank = 1; incomingRank <= list.documents().size(); incomingRank++) {
        Document candidate = list.documents().get(incomingRank - 1);
        Integer selectedRank = rankInSelection.get(candidate.getId());
        boolean kept = selectedRank != null;
        verdicts.add(
            CandidateVerdict.of(
                candidate,
                kept ? CandidateOutcome.KEPT : CandidateOutcome.DROPPED,
                kept ? VerdictReason.WITHIN_BUDGET : VerdictReason.OUTSIDE_LIST_BUDGET,
                list.label(),
                kept ? selectedRank : incomingRank,
                candidate.getScore()));
      }
    }

    int outgoing = narrowed.stream().mapToInt(list -> list.documents().size()).sum();
    return new StageOutcome(
        state.withCandidateLists(narrowed),
        StageExplanation.executed(
            name(),
            incoming,
            outgoing,
            verdicts,
            List.of(
                RetrievalNote.PER_LIST_BUDGET.format(context.candidateBudget()),
                properties.mmrLambda() >= 1.0
                    ? RetrievalNote.MMR_LAMBDA_INACTIVE.format(properties.mmrLambda())
                    : RetrievalNote.MMR_LAMBDA_ACTIVE.format(properties.mmrLambda()))));
  }

  /** One pooled lookup for the whole run, skipped entirely at {@code mmrLambda >= 1.0}. */
  private Map<String, float[]> lookupEmbeddings(
      List<Document> candidatePool, QueryProperties properties) {
    return properties.mmrLambda() >= 1.0
        ? Map.of()
        : chunkEmbeddingLookup.findByIds(candidatePool.stream().map(Document::getId).toList());
  }
}
