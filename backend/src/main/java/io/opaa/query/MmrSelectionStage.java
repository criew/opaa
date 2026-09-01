package io.opaa.query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * Step 4 of docs/features/retrieval-algorithm.md as a pipeline stage: narrows every candidate list
 * to {@link RetrievalContext#candidateBudget()} via {@link MmrSelector}, each list on its own - MMR
 * runs inside one sub-query's single-topic candidate pool, never across the pooled cross-topic
 * result. That budget is {@link QueryProperties#topK} unless reranking runs, in which case it is the
 * wider rerank candidate window: a reranker that only ever saw {@code top-k} candidates could not
 * promote anything the earlier stages had already dropped, which is the entire point of the stage
 * (docs/features/hybrid-retrieval.md, Arbeitspaket 4).
 *
 * <p>The chunk embeddings MMR needs are read back once for the whole run, over the pooled
 * candidates, rather than once per list: {@link ChunkEmbeddingLookup} does not care which list a
 * candidate came from. At {@link QueryProperties#mmrLambda} {@code >= 1.0} the diversity term is
 * always multiplied by zero (see {@link MmrSelector#select}), so the round trip is skipped entirely
 * - it could not affect the result.
 *
 * <p>Narrows only: every chunk it passes on was already permission-scoped by the search that
 * produced it, and threshold-filtered if that search was the vector one - {@link
 * QueryProperties#similarityThreshold} is a property of vector distance and has no counterpart in
 * the lexical path, whose lists this stage narrows by the same per-list budget all the same.
 *
 * <p><b>At {@link QueryProperties#mmrLambda} {@code < 1.0} this stage treats the two paths' lists
 * unequally</b>, and knowingly so: the relevance term is each candidate's own score, which is a
 * cosine similarity in a vector list and a {@code ts_rank} an order of magnitude smaller in a
 * lexical one, while the diversity term is a cosine similarity in both. A lexical list is therefore
 * ordered almost entirely by diversity at any lambda below 1.0 - see {@link MmrSelector}'s scale
 * note. The shipped default is {@code 1.0}, where the diversity term is multiplied by zero and both
 * paths are narrowed identically.
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
              list.documents(),
              context.candidateBudget(),
              properties.mmrLambda(),
              embeddings);
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
                "per-list budget " + context.candidateBudget(),
                "mmr-lambda "
                    + properties.mmrLambda()
                    + (properties.mmrLambda() >= 1.0
                        ? " (diversity term inactive: plain top-k by relevance)"
                        : " (diversity term active, cosine similarity of real chunk embeddings)"))));
  }

  /**
   * One pooled lookup for the whole run, skipped entirely at {@code mmrLambda >= 1.0} - see this
   * class's Javadoc.
   */
  private Map<String, float[]> lookupEmbeddings(
      List<Document> candidatePool, QueryProperties properties) {
    return properties.mmrLambda() >= 1.0
        ? Map.of()
        : chunkEmbeddingLookup.findByIds(candidatePool.stream().map(Document::getId).toList());
  }
}
