package io.opaa.query;

import io.opaa.query.DocumentCompletion.CompletionEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * Step 6 of docs/features/retrieval-algorithm.md as a pipeline stage: lets a document already
 * represented in the selection contribute up to {@link QueryProperties#maxChunksPerDocument} chunks
 * (#932/#935), drawn only from {@link RetrievalState#candidatePool()} - the candidates the search
 * stages already returned under the permission filter and the similarity threshold. It therefore
 * widens neither of them, and runs last for the same reason it did before: it works on the final
 * ranking, not on a stage a later one would resort.
 *
 * <p>The verdicts distinguish the two eviction tiers, because for the diagnosis they are two
 * different answers: a tier-1 eviction takes a chunk from a document that keeps another one, a
 * tier-2 eviction can remove a document from the answer entirely.
 */
@Component
class DocumentCompletionStage implements RetrievalStage {

  @Override
  public RetrievalStageName name() {
    return RetrievalStageName.DOCUMENT_COMPLETION;
  }

  @Override
  public StageOutcome apply(RetrievalContext context, RetrievalState state) {
    QueryProperties properties = context.queryProperties();
    List<Document> selection = state.selection();
    List<CompletionEvent> events = new ArrayList<>();
    List<Document> completed =
        DocumentCompletion.complete(
            selection,
            state.candidatePool(),
            properties.maxChunksPerDocument(),
            properties.topK(),
            events);

    Set<String> survivingChunkIds = new HashSet<>();
    completed.forEach(document -> survivingChunkIds.add(document.getId()));

    List<CandidateVerdict> verdicts = new ArrayList<>();
    for (Document candidate : selection) {
      boolean kept = survivingChunkIds.contains(candidate.getId());
      verdicts.add(
          CandidateVerdict.of(
              candidate,
              kept ? CandidateOutcome.KEPT : CandidateOutcome.DROPPED,
              kept ? VerdictReason.WITHIN_BUDGET : evictionReasonFor(candidate, events),
              RankFusionStage.FUSED_LIST_LABEL,
              null));
    }
    for (CompletionEvent event : events) {
      verdicts.add(
          CandidateVerdict.of(
              event.added(),
              CandidateOutcome.ADDED,
              VerdictReason.COMPLETED_AS_SIBLING,
              RankFusionStage.FUSED_LIST_LABEL,
              null));
    }

    return new StageOutcome(
        state.withCandidateLists(
            List.of(new CandidateList(RankFusionStage.FUSED_LIST_LABEL, completed))),
        StageExplanation.executed(
            name(),
            selection.size(),
            completed.size(),
            verdicts,
            List.of(
                "max-chunks-per-document " + properties.maxChunksPerDocument(),
                "overall budget top-k " + properties.topK(),
                events.size() + " sibling chunk(s) completed from the candidate pool")));
  }

  /**
   * Which tier displaced {@code candidate}, taken from the completion's own trace rather than
   * re-derived here - a second derivation would be a second implementation of the eviction rules,
   * free to drift from the one that actually ran. A chunk can leave the selection only through an
   * eviction, so a missing trace entry is a broken invariant rather than a case to guess at.
   */
  private static VerdictReason evictionReasonFor(Document candidate, List<CompletionEvent> events) {
    for (CompletionEvent event : events) {
      if (event.evicted() != null && event.evicted().getId().equals(candidate.getId())) {
        return event.evictionTier() == 1
            ? VerdictReason.EVICTED_BY_DOCUMENT_COMPLETION_TIER_1
            : VerdictReason.EVICTED_BY_DOCUMENT_COMPLETION_TIER_2;
      }
    }
    throw new IllegalStateException(
        "chunk "
            + candidate.getId()
            + " left the selection without a document-completion eviction");
  }
}
