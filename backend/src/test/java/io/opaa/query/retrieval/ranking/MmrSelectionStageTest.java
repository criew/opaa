package io.opaa.query.retrieval.ranking;

import static io.opaa.query.retrieval.RetrievalPipelineTestSupport.chunk;
import static io.opaa.query.retrieval.RetrievalPipelineTestSupport.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.llm.RerankModelRole;
import io.opaa.query.QueryProperties;
import io.opaa.query.retrieval.CandidateOutcome;
import io.opaa.query.retrieval.RetrievalPipeline;
import io.opaa.query.retrieval.RetrievalPipelineProperties;
import io.opaa.query.retrieval.RetrievalPipelineResult;
import io.opaa.query.retrieval.RetrievalPipelineTestSupport;
import io.opaa.query.retrieval.RetrievalStageName;
import io.opaa.query.retrieval.StageExplanation;
import io.opaa.query.retrieval.VerdictReason;
import io.opaa.query.retrieval.search.FullTextChunkSearch;
import io.opaa.query.retrieval.search.QueryDecompositionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * The per-list budget stage inside the pipeline it belongs to (docs/handbuch/suche.md, Stufe 6):
 * what it drops carries its verdict, and taking it out leaves the budget to fusion alone.
 */
class MmrSelectionStageTest {

  private static final UUID LIBRARY_ID = UUID.randomUUID();
  private static final QueryProperties PROPERTIES =
      new QueryProperties(8, 25, 1.0, 0.3, 1.0, false, 3, 2, false, 50);

  private final VectorStore vectorStore = mock(VectorStore.class);

  private RetrievalPipeline pipeline(RetrievalPipelineProperties pipelineProperties) {
    return RetrievalPipelineTestSupport.pipeline(
        vectorStore,
        mock(FullTextChunkSearch.class),
        mock(ChunkEmbeddingLookup.class),
        mock(QueryDecompositionService.class),
        mock(RerankModelRole.class),
        pipelineProperties);
  }

  private void stubSearch(List<Document> results) {
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(results);
  }

  /**
   * "Abgeschaltet = Identität" for the MMR stage: without it, no per-list narrowing happens at all,
   * so the full {@code fetch-k} lists reach fusion, which then enforces the only remaining budget.
   * The switch must remove the stage, not neutralize it - a pipeline that still truncated per list
   * would measure the diversity term alone rather than the stage's contribution.
   */
  @Test
  void switchingOffTheMmrStageLeavesTheFullListsToFusion() {
    List<Document> candidates = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      candidates.add(chunk("chunk-" + i, "doc-" + i, 0.9 - i * 0.01));
    }
    stubSearch(List.copyOf(candidates));

    RetrievalPipelineResult withoutMmr =
        pipeline(new RetrievalPipelineProperties(Set.of(RetrievalStageName.MMR_SELECTION)))
            .run(context(Set.of(LIBRARY_ID), PROPERTIES));

    // Fusion still caps at top-k, and the order is the search order: with one list, RRF ranks by
    // that list's own ranks.
    assertThat(withoutMmr.chunks())
        .extracting(Document::getId)
        .containsExactly(
            "chunk-0", "chunk-1", "chunk-2", "chunk-3", "chunk-4", "chunk-5", "chunk-6", "chunk-7");
    StageExplanation fusion =
        withoutMmr.explanation().stages().stream()
            .filter(stage -> stage.stage() == RetrievalStageName.RANK_FUSION)
            .findFirst()
            .orElseThrow();
    assertThat(fusion.incomingCount()).isEqualTo(12);
  }

  /**
   * The protocol answers the diagnosis question the specification is written for: was the document
   * never found, or found and displaced? A chunk that lost the per-list budget carries exactly that
   * verdict, on the stage that made the decision.
   */
  @Test
  void aDisplacedCandidateCarriesTheStageAndReasonThatDisplacedIt() {
    List<Document> candidates = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      candidates.add(chunk("chunk-" + i, "doc-" + i, 0.9 - i * 0.01));
    }
    stubSearch(List.copyOf(candidates));

    RetrievalPipelineResult result =
        pipeline(RetrievalPipelineProperties.allStagesEnabled())
            .run(context(Set.of(LIBRARY_ID), PROPERTIES));

    assertThat(result.chunks()).hasSize(PROPERTIES.topK());
    List<StageExplanation> droppedIn = result.explanation().stagesThatDropped("chunk-11");
    assertThat(droppedIn)
        .extracting(StageExplanation::stage)
        .contains(RetrievalStageName.MMR_SELECTION);
    assertThat(result.explanation().forChunk("chunk-11"))
        .anySatisfy(
            verdict -> {
              assertThat(verdict.outcome()).isEqualTo(CandidateOutcome.DROPPED);
              assertThat(verdict.reason()).isEqualTo(VerdictReason.OUTSIDE_LIST_BUDGET);
              assertThat(verdict.value()).isNotNull();
            });
  }
}
