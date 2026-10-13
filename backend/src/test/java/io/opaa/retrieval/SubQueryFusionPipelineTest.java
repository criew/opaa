package io.opaa.retrieval;

import static io.opaa.retrieval.RetrievalPipelineTestSupport.chunk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.llm.RerankModelRole;
import io.opaa.metadata.MetadataFilter;
import io.opaa.retrieval.ranking.ChunkEmbeddingLookup;
import io.opaa.retrieval.search.FullTextChunkSearch;
import io.opaa.retrieval.search.FullTextSearchResult;
import io.opaa.retrieval.search.QueryDecompositionService;
import io.opaa.retrieval.search.VectorChunkSearch;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

/**
 * The quota per sub-query through the whole pipeline (docs/features/retrieval-algorithm.md, step
 * 5): the leader of each sub-query reaches the selection although chunks the sub-queries share
 * collect more rank contributions, and the fusion's protocol says which list a candidate owes its
 * place to.
 */
class SubQueryFusionPipelineTest {

  private static final UUID LIBRARY_ID = UUID.randomUUID();

  private final VectorChunkSearch vectorChunkSearch = mock(VectorChunkSearch.class);
  private final QueryDecompositionService decomposition = mock(QueryDecompositionService.class);
  private final ChunkEmbeddingLookup chunkEmbeddingLookup = mock(ChunkEmbeddingLookup.class);

  private static QueryProperties properties(double mmrLambda, boolean quota) {
    return new QueryProperties(3, 25, mmrLambda, 0.3, true, 3, 1, false, 50, 20, 2)
        .withFusion(new QueryProperties.Fusion(FusionMethod.RRF, quota, 0.7, 0));
  }

  private RetrievalPipelineResult run(QueryProperties properties) {
    when(decomposition.decompose(any(), any(Integer.class)))
        .thenReturn(Optional.of(List.of("ginster", "elfe")));
    stubVectorSearch("ginster", "ginster-chunk");
    stubVectorSearch("elfe", "elfe-chunk");
    when(chunkEmbeddingLookup.findByIds(anyList())).thenReturn(Map.of());
    RetrievalPipeline pipeline =
        RetrievalPipelineTestSupport.pipeline(
            vectorChunkSearch,
            mock(FullTextChunkSearch.class),
            chunkEmbeddingLookup,
            decomposition,
            mock(RerankModelRole.class),
            RetrievalPipelineProperties.allStagesEnabled());
    return pipeline.run(
        new RetrievalContext(
            "Frage",
            List.of(),
            Set.of(LIBRARY_ID),
            MetadataFilter.NONE,
            properties,
            RerankAvailability.SWITCHED_OFF));
  }

  private void stubVectorSearch(String query, String leader) {
    when(vectorChunkSearch.similaritySearch(
            argThat(
                (SearchRequest request) -> request != null && query.equals(request.getQuery()))))
        .thenReturn(
            List.of(
                chunk(leader, "doc-" + leader, 0.70),
                chunk("shared-1", "doc-shared-1", 0.60),
                chunk("shared-2", "doc-shared-2", 0.59),
                chunk("shared-3", "doc-shared-3", 0.58)));
  }

  /**
   * Holds for MMR on and off: the MMR stage hands on lists that still know their sub-query, or the
   * quota would silently fall back to one pool.
   */
  @ParameterizedTest
  @ValueSource(doubles = {1.0, 0.5})
  void theLeaderOfEverySubQueryReachesTheSelection(double mmrLambda) {
    RetrievalPipelineResult pooled = run(properties(mmrLambda, false));
    RetrievalPipelineResult quota = run(properties(mmrLambda, true));

    assertThat(pooled.chunks())
        .extracting(Document::getId)
        .startsWith("shared-1", "shared-2")
        .doesNotContain("elfe-chunk");
    assertThat(quota.chunks())
        .extracting(Document::getId)
        .containsExactly("ginster-chunk", "elfe-chunk", "shared-1");
  }

  @Test
  void theFusionProtocolNamesTheContributingListsAndTheQuota() {
    RetrievalPipelineResult result = run(properties(1.0, true));

    StageExplanation fusion =
        result.explanation().stages().stream()
            .filter(stage -> stage.stage() == RetrievalStageName.RANK_FUSION)
            .findFirst()
            .orElseThrow();
    assertThat(fusion.notes()).contains(RetrievalNote.FUSION_SUB_QUERY_QUOTA.format(2));
    assertThat(fusion.verdicts())
        .filteredOn(verdict -> verdict.chunkId().equals("shared-1"))
        .singleElement()
        .satisfies(
            verdict ->
                assertThat(verdict.contributions())
                    .extracting(FusionContribution::listLabel, FusionContribution::rank)
                    .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("vector search · sub-query 1", 2)));
    // only what counts towards the shown value: the placing sub-query's lists, never the others'
    assertThat(fusion.verdicts())
        .filteredOn(verdict -> verdict.chunkId().equals("shared-1"))
        .singleElement()
        .satisfies(
            verdict -> {
              assertThat(verdict.listLabel()).isEqualTo("fused · sub-query 1");
              assertThat(
                      verdict.contributions().stream().mapToDouble(FusionContribution::value).sum())
                  .isEqualTo(verdict.value());
            });
    assertThat(fusion.verdicts())
        .filteredOn(verdict -> verdict.chunkId().equals("elfe-chunk"))
        .singleElement()
        .satisfies(verdict -> assertThat(verdict.listLabel()).isEqualTo("fused · sub-query 2"));
  }

  /**
   * Without decomposition there is one search query, and the quota is the identity with the lexical
   * path switched on too: the pooled and the quota run select the same chunks in the same order.
   */
  @Test
  void withOneSearchQueryAndBothPathsTheQuotaChangesNothing() {
    FullTextChunkSearch fullTextChunkSearch = mock(FullTextChunkSearch.class);
    when(vectorChunkSearch.similaritySearch(any(SearchRequest.class)))
        .thenReturn(
            List.of(
                chunk("v1", "doc-v1", 0.8),
                chunk("both", "doc-both", 0.7),
                chunk("v2", "doc-v2", 0.6),
                chunk("v3", "doc-v3", 0.5)));
    when(fullTextChunkSearch.search(anyString(), any(), any(), any(), anyInt()))
        .thenReturn(
            new FullTextSearchResult(
                List.of(
                    chunk("t1", "doc-t1", 0.09),
                    chunk("both", "doc-both", 0.08),
                    chunk("t2", "doc-t2", 0.07)),
                List.of()));
    RetrievalPipeline pipeline =
        RetrievalPipelineTestSupport.pipeline(
            vectorChunkSearch,
            fullTextChunkSearch,
            chunkEmbeddingLookup,
            decomposition,
            mock(RerankModelRole.class),
            RetrievalPipelineProperties.allStagesEnabled());

    List<List<String>> selections = new java.util.ArrayList<>();
    for (boolean quota : List.of(false, true)) {
      QueryProperties properties =
          new QueryProperties(5, 25, 1.0, 0.3, false, 3, 1, true, 50, 20, 2)
              .withFusion(new QueryProperties.Fusion(FusionMethod.RRF, quota, 0.7, 0));
      selections.add(
          pipeline
              .run(
                  new RetrievalContext(
                      "Frage",
                      List.of(),
                      Set.of(LIBRARY_ID),
                      MetadataFilter.NONE,
                      properties,
                      RerankAvailability.SWITCHED_OFF))
              .chunks()
              .stream()
              .map(Document::getId)
              .toList());
    }

    assertThat(selections.get(0)).startsWith("both").hasSize(5);
    assertThat(selections.get(1)).isEqualTo(selections.get(0));
  }
}
