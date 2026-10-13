package io.opaa.retrieval.ranking;

import io.opaa.retrieval.CandidateList;
import io.opaa.retrieval.CandidateOutcome;
import io.opaa.retrieval.CandidateVerdict;
import io.opaa.retrieval.QueryProperties;
import io.opaa.retrieval.RetrievalContext;
import io.opaa.retrieval.RetrievalNote;
import io.opaa.retrieval.RetrievalStage;
import io.opaa.retrieval.RetrievalStageName;
import io.opaa.retrieval.RetrievalState;
import io.opaa.retrieval.StageExplanation;
import io.opaa.retrieval.StageOutcome;
import io.opaa.retrieval.StageStatus;
import io.opaa.retrieval.VerdictReason;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * The {@link RetrievalStageName#MMR_SELECTION} stage: reorders every candidate list by {@link
 * MmrSelector}, each list on its own - MMR runs inside one search query's candidate pool, never
 * across the pooled cross-topic result. It never narrows: every {@code fetch-k} candidate reaches
 * the fusion, which alone enforces the budget.
 *
 * <p>At {@link QueryProperties#mmrLambda} {@code >= 1.0} the diversity term is multiplied by zero
 * and MMR would reproduce each list's own order, so the stage does not run and reports itself as
 * {@link StageStatus#DISABLED}; {@link ChunkEmbeddingLookup} is not called.
 *
 * <p>Below {@code 1.0} the two paths' lists are treated unequally and knowingly so: the relevance
 * term is the candidate's own score - a cosine similarity here, a much smaller {@code ts_rank}
 * there - while the diversity term is a cosine similarity in both.
 */
@Component
public class MmrSelectionStage implements RetrievalStage {

  private final ChunkEmbeddingLookup chunkEmbeddingLookup;

  public MmrSelectionStage(ChunkEmbeddingLookup chunkEmbeddingLookup) {
    this.chunkEmbeddingLookup = chunkEmbeddingLookup;
  }

  @Override
  public RetrievalStageName name() {
    return RetrievalStageName.MMR_SELECTION;
  }

  @Override
  public StageOutcome apply(RetrievalContext context, RetrievalState state) {
    QueryProperties properties = context.queryProperties();
    int incoming = state.candidateCount();
    if (properties.mmrLambda() >= 1.0) {
      return new StageOutcome(
          state,
          StageExplanation.notRun(
              name(),
              StageStatus.DISABLED,
              incoming,
              incoming,
              RetrievalNote.MMR_LAMBDA_INACTIVE.format(properties.mmrLambda())));
    }

    Map<String, float[]> embeddings =
        chunkEmbeddingLookup.findByIds(
            state.candidatePool().stream().map(Document::getId).toList());
    List<CandidateList> reordered = new ArrayList<>(state.candidateLists().size());
    List<CandidateVerdict> verdicts = new ArrayList<>(incoming);
    for (CandidateList list : state.candidateLists()) {
      List<Document> ordered =
          MmrSelector.select(
              list.documents(), list.documents().size(), properties.mmrLambda(), embeddings);
      reordered.add(list.withDocuments(ordered));
      for (int i = 0; i < ordered.size(); i++) {
        Document candidate = ordered.get(i);
        verdicts.add(
            CandidateVerdict.of(
                candidate,
                CandidateOutcome.KEPT,
                VerdictReason.WITHIN_BUDGET,
                list.label(),
                i + 1,
                candidate.getScore()));
      }
    }

    return new StageOutcome(
        state.withCandidateLists(reordered),
        StageExplanation.executed(
            name(),
            incoming,
            incoming,
            verdicts,
            List.of(RetrievalNote.MMR_LAMBDA_ACTIVE.format(properties.mmrLambda()))));
  }
}
