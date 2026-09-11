package io.opaa.query.retrieval.ranking;

import static io.opaa.query.retrieval.RetrievalPipelineTestSupport.chunk;
import static io.opaa.query.retrieval.RetrievalPipelineTestSupport.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.opaa.query.QueryProperties;
import io.opaa.query.retrieval.CandidateList;
import io.opaa.query.retrieval.CandidateOutcome;
import io.opaa.query.retrieval.CandidateVerdict;
import io.opaa.query.retrieval.RetrievalContext;
import io.opaa.query.retrieval.RetrievalState;
import io.opaa.query.retrieval.StageOutcome;
import io.opaa.query.retrieval.VerdictReason;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/**
 * The budget of the fusion stage (docs/handbuch/suche.md, Stufe 7): {@link ReciprocalRankFusion}
 * ranks without a cap, the stage applies it and says of every candidate beyond it why it went.
 */
class RankFusionStageTest {

  private static final UUID LIBRARY_ID = UUID.randomUUID();

  private static QueryProperties propertiesWithTopK(int topK) {
    return new QueryProperties(topK, 25, 1.0, 0.3, false, 3, 2, false, 50);
  }

  private static RetrievalState stateWith(List<Document> candidates) {
    return RetrievalState.initial()
        .withSearchResults(List.of(new CandidateList("vector search · sub-query 1", candidates)));
  }

  @Test
  void theFusedOrderIsCutToTheCandidateBudget() {
    List<Document> candidates =
        List.of(
            chunk("a", "doc-a", 0.4),
            chunk("b", "doc-b", 0.3),
            chunk("c", "doc-c", 0.2),
            chunk("d", "doc-d", 0.1));

    StageOutcome outcome =
        new RankFusionStage()
            .apply(context(Set.of(LIBRARY_ID), propertiesWithTopK(2)), stateWith(candidates));

    assertThat(outcome.state().selection()).extracting(Document::getId).containsExactly("a", "b");
    assertThat(outcome.explanation().outgoingCount()).isEqualTo(2);
    assertThat(outcome.explanation().verdicts())
        .filteredOn(verdict -> verdict.outcome() == CandidateOutcome.DROPPED)
        .extracting(CandidateVerdict::chunkId, CandidateVerdict::reason)
        .containsExactly(
            tuple("c", VerdictReason.OUTSIDE_FUSION_BUDGET),
            tuple("d", VerdictReason.OUTSIDE_FUSION_BUDGET));
  }

  /**
   * The stage can never be asked for a non-positive budget: {@code top-k} is normalised to a
   * positive value, and the rerank window only ever widens it. The fusion therefore always hands on
   * at least one candidate when it has one.
   */
  @Test
  void theCandidateBudgetIsNeverNonPositive() {
    RetrievalContext withoutConfiguredTopK = context(Set.of(LIBRARY_ID), propertiesWithTopK(0));

    assertThat(withoutConfiguredTopK.candidateBudget()).isPositive();

    StageOutcome outcome =
        new RankFusionStage()
            .apply(withoutConfiguredTopK, stateWith(List.of(chunk("a", "doc-a", 0.4))));

    assertThat(outcome.state().selection()).extracting(Document::getId).containsExactly("a");
  }
}
