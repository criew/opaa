package io.opaa.retrieval.ranking;

import static io.opaa.retrieval.RetrievalPipelineTestSupport.chunk;
import static io.opaa.retrieval.RetrievalPipelineTestSupport.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.CandidateOutcome;
import io.opaa.api.types.RetrievalStageName;
import io.opaa.api.types.VerdictReason;
import io.opaa.llm.RerankModelRole;
import io.opaa.retrieval.CandidateVerdict;
import io.opaa.retrieval.QueryProperties;
import io.opaa.retrieval.RetrievalPipeline;
import io.opaa.retrieval.RetrievalPipelineProperties;
import io.opaa.retrieval.RetrievalPipelineResult;
import io.opaa.retrieval.RetrievalPipelineTestSupport;
import io.opaa.retrieval.StageExplanation;
import io.opaa.retrieval.StageStatus;
import io.opaa.retrieval.search.FullTextChunkSearch;
import io.opaa.retrieval.search.QueryDecompositionService;
import io.opaa.retrieval.search.VectorChunkSearch;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

/**
 * The MMR stage inside the pipeline it belongs to (docs/handbuch/spaces/suche.md, Stufe 6): it
 * reorders the {@code fetch-k} candidates of every list and never narrows them, so the fusion sees
 * every candidate a search returned; at {@code mmr-lambda} 1.0 it does not run at all.
 */
class MmrSelectionStageTest {

  private static final UUID LIBRARY_ID = UUID.randomUUID();
  private static final QueryProperties PROPERTIES =
      new QueryProperties(8, 25, 1.0, 0.3, false, 3, 2, false, 50, 20, 2);
  private static final QueryProperties DIVERSIFYING =
      new QueryProperties(8, 25, 0.5, 0.3, false, 3, 2, false, 50, 20, 2);

  private final VectorChunkSearch vectorChunkSearch = mock(VectorChunkSearch.class);
  private final ChunkEmbeddingLookup chunkEmbeddingLookup = mock(ChunkEmbeddingLookup.class);

  private RetrievalPipeline pipeline(RetrievalPipelineProperties pipelineProperties) {
    return RetrievalPipelineTestSupport.pipeline(
        vectorChunkSearch,
        mock(FullTextChunkSearch.class),
        chunkEmbeddingLookup,
        mock(QueryDecompositionService.class),
        mock(RerankModelRole.class),
        pipelineProperties);
  }

  private List<Document> stubTwelveCandidates() {
    List<Document> candidates = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      candidates.add(chunk("chunk-" + i, "doc-" + i, 0.9 - i * 0.01));
    }
    when(vectorChunkSearch.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.copyOf(candidates));
    return candidates;
  }

  private static StageExplanation stage(RetrievalPipelineResult result, RetrievalStageName name) {
    return result.explanation().stages().stream()
        .filter(stage -> stage.stage() == name)
        .findFirst()
        .orElseThrow();
  }

  /**
   * "Abgeschaltet = Identität" for the MMR stage: without it the full {@code fetch-k} lists reach
   * fusion, which enforces the only budget.
   */
  @Test
  void switchingOffTheMmrStageLeavesTheFullListsToFusion() {
    stubTwelveCandidates();

    RetrievalPipelineResult withoutMmr =
        pipeline(new RetrievalPipelineProperties(Set.of(RetrievalStageName.MMR_SELECTION)))
            .run(context(Set.of(LIBRARY_ID), PROPERTIES));

    assertThat(withoutMmr.chunks())
        .extracting(Document::getId)
        .containsExactly(
            "chunk-0", "chunk-1", "chunk-2", "chunk-3", "chunk-4", "chunk-5", "chunk-6", "chunk-7");
    assertThat(stage(withoutMmr, RetrievalStageName.RANK_FUSION).incomingCount()).isEqualTo(12);
  }

  /**
   * Regression guard for #2620: a candidate ranked below {@code top-k} but within {@code fetch-k}
   * in its own list reaches the fusion and is judged there, not cut off before it.
   */
  @Test
  void aCandidateBelowTopKButWithinFetchKReachesTheFusion() {
    stubTwelveCandidates();

    RetrievalPipelineResult result =
        pipeline(RetrievalPipelineProperties.allStagesEnabled())
            .run(context(Set.of(LIBRARY_ID), PROPERTIES));

    StageExplanation fusion = stage(result, RetrievalStageName.RANK_FUSION);
    assertThat(fusion.incomingCount()).isEqualTo(12);
    assertThat(fusion.verdicts())
        .filteredOn(verdict -> verdict.chunkId().equals("chunk-11"))
        .singleElement()
        .satisfies(
            verdict -> {
              assertThat(verdict.outcome()).isEqualTo(CandidateOutcome.DROPPED);
              assertThat(verdict.reason()).isEqualTo(VerdictReason.OUTSIDE_FUSION_BUDGET);
            });
    assertThat(result.chunks()).hasSize(PROPERTIES.topK());
  }

  /**
   * At {@code mmr-lambda} 1.0 the diversity term vanishes, so the stage would only copy the lists:
   * it is skipped, reported as switched off, and reads no embeddings.
   */
  @Test
  void atMmrLambdaOneTheStageIsSkippedAndReportedAsDisabled() {
    stubTwelveCandidates();

    RetrievalPipelineResult result =
        pipeline(RetrievalPipelineProperties.allStagesEnabled())
            .run(context(Set.of(LIBRARY_ID), PROPERTIES));

    StageExplanation mmr = stage(result, RetrievalStageName.MMR_SELECTION);
    assertThat(mmr.status()).isEqualTo(StageStatus.DISABLED);
    assertThat(mmr.incomingCount()).isEqualTo(12);
    assertThat(mmr.outgoingCount()).isEqualTo(12);
    assertThat(mmr.verdicts()).isEmpty();
    assertThat(mmr.notes()).singleElement().asString().contains("mmr-lambda 1.0");
    verify(chunkEmbeddingLookup, never()).findByIds(anyList());
  }

  /**
   * Below 1.0 the stage reorders every list by MMR and passes on all of its candidates: nothing is
   * dropped before the fusion, and a near-duplicate of the top chunk falls back in the order.
   */
  @Test
  void belowMmrLambdaOneEveryListIsReorderedButNotNarrowed() {
    List<Document> candidates = stubTwelveCandidates();
    Map<String, float[]> embeddings = new HashMap<>();
    for (int i = 0; i < candidates.size(); i++) {
      embeddings.put(candidates.get(i).getId(), new float[] {1.0f, i});
    }
    // chunk-1 repeats chunk-0 exactly
    embeddings.put("chunk-1", new float[] {1.0f, 0.0f});
    when(chunkEmbeddingLookup.findByIds(anyList())).thenReturn(embeddings);

    RetrievalPipelineResult result =
        pipeline(RetrievalPipelineProperties.allStagesEnabled())
            .run(context(Set.of(LIBRARY_ID), DIVERSIFYING));

    StageExplanation mmr = stage(result, RetrievalStageName.MMR_SELECTION);
    assertThat(mmr.status()).isEqualTo(StageStatus.EXECUTED);
    assertThat(mmr.incomingCount()).isEqualTo(12);
    assertThat(mmr.outgoingCount()).isEqualTo(12);
    assertThat(mmr.verdicts())
        .extracting(CandidateVerdict::outcome)
        .containsOnly(CandidateOutcome.KEPT);
    assertThat(mmr.verdicts())
        .filteredOn(verdict -> verdict.chunkId().equals("chunk-1"))
        .singleElement()
        .satisfies(verdict -> assertThat(verdict.rank()).isGreaterThan(2));
    assertThat(stage(result, RetrievalStageName.RANK_FUSION).incomingCount()).isEqualTo(12);
  }
}
