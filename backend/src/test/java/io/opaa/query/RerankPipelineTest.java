package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.llm.RerankClient.ScoredCandidate;
import io.opaa.llm.RerankModelRole;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * Reranking at the level it is claimed on (docs/features/hybrid-retrieval.md, Arbeitspaket 4): the
 * whole pipeline as {@link QueryConfiguration} wires it, with the rerank stage between fusion and
 * document completion.
 *
 * <p>The budget hand-off is what these tests pin. Fusion keeps the rerank candidate window instead
 * of {@code top-k} while reranking runs, and the rerank stage cuts back to {@code top-k} - so the
 * number of chunks leaving the pipeline is the same with and without reranking, whatever happens to
 * the endpoint in between.
 */
class RerankPipelineTest {

  private static final UUID LIBRARY_ID = UUID.randomUUID();
  private static final int TOP_K = 8;
  private static final int CANDIDATE_WINDOW = 20;

  private static final QueryProperties WITH_RERANKING =
      new QueryProperties(TOP_K, 25, 1.0, 0.3, 1.0, false, 3, 1, false, CANDIDATE_WINDOW);
  private static final QueryProperties WITHOUT_RERANKING =
      new QueryProperties(TOP_K, 25, 1.0, 0.3, 1.0, false, 3, 1, false, 0);

  private final VectorStore vectorStore = mock(VectorStore.class);
  private final ChunkEmbeddingLookup chunkEmbeddingLookup = mock(ChunkEmbeddingLookup.class);
  private final QueryDecompositionService queryDecompositionService =
      mock(QueryDecompositionService.class);
  private final RerankModelRole rerankModelRole = mock(RerankModelRole.class);

  private RetrievalPipeline pipeline() {
    return pipeline(RetrievalPipelineProperties.allStagesEnabled());
  }

  private RetrievalPipeline pipeline(RetrievalPipelineProperties pipelineProperties) {
    return new QueryConfiguration()
        .retrievalPipeline(
            new SearchScopeStage(),
            new SubQueryDecompositionStage(queryDecompositionService),
            new VectorSearchStage(vectorStore),
            // The lexical path is switched off in every QueryProperties here: this class is about
            // what happens to the fused list afterwards, not about how it was retrieved.
            new FullTextSearchStage(
                mock(FullTextChunkSearch.class), mock(FullTextBackfillGate.class)),
            new MmrSelectionStage(chunkEmbeddingLookup),
            new RankFusionStage(),
            new RerankStage(rerankModelRole),
            new DocumentCompletionStage(),
            pipelineProperties);
  }

  private static Document chunk(int i) {
    return Document.builder()
        .id("c" + i)
        .text("Text c" + i)
        .metadata(Map.of("document_id", "doc-c" + i, "file_name", "c" + i + ".md"))
        .score(1.0 - i / 100.0)
        .build();
  }

  private RetrievalPipelineResult run(QueryProperties properties, RerankAvailability availability) {
    return run(pipeline(), properties, availability);
  }

  private RetrievalPipelineResult run(
      RetrievalPipeline pipeline, QueryProperties properties, RerankAvailability availability) {
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(IntStream.range(0, 25).mapToObj(RerankPipelineTest::chunk).toList());
    return pipeline.run(
        new RetrievalContext("Frage", List.of(), Set.of(LIBRARY_ID), properties, availability));
  }

  private static StageExplanation stageOf(
      RetrievalPipelineResult result, RetrievalStageName stage) {
    return result.explanation().stages().stream()
        .filter(explanation -> explanation.stage() == stage)
        .findFirst()
        .orElseThrow();
  }

  /**
   * The reranker's verdict decides the answer: the chunk the vector path ranked last comes first
   * once the model says so.
   */
  @Test
  void theRerankedOrderDecidesTheSelection() {
    when(rerankModelRole.rerank(anyString(), any()))
        .thenReturn(
            IntStream.range(0, CANDIDATE_WINDOW)
                .mapToObj(i -> new ScoredCandidate(i, i))
                .toList()
                .reversed());

    RetrievalPipelineResult result = run(WITH_RERANKING, RerankAvailability.USABLE);

    assertThat(result.chunks()).hasSize(TOP_K);
    assertThat(result.chunks().getFirst().getId()).isEqualTo("c19");
  }

  /** Fusion keeps the candidate window for the reranker, and the reranker gives back top-k. */
  @Test
  void fusionKeepsTheCandidateWindowAndTheRerankStageRestoresTopK() {
    when(rerankModelRole.rerank(anyString(), any()))
        .thenReturn(
            IntStream.range(0, CANDIDATE_WINDOW)
                .mapToObj(i -> new ScoredCandidate(i, -i))
                .toList());

    RetrievalPipelineResult result = run(WITH_RERANKING, RerankAvailability.USABLE);

    assertThat(stageOf(result, RetrievalStageName.RANK_FUSION).outgoingCount())
        .isEqualTo(CANDIDATE_WINDOW);
    assertThat(stageOf(result, RetrievalStageName.RERANK).outgoingCount()).isEqualTo(TOP_K);
    assertThat(result.chunks()).hasSize(TOP_K);
  }

  /** Without reranking the pipeline behaves exactly as it did before this stage existed. */
  @Test
  void withoutRerankingFusionKeepsTopKAndTheStageIsIdentity() {
    RetrievalPipelineResult result = run(WITHOUT_RERANKING, RerankAvailability.SWITCHED_OFF);

    assertThat(stageOf(result, RetrievalStageName.RANK_FUSION).outgoingCount()).isEqualTo(TOP_K);
    assertThat(stageOf(result, RetrievalStageName.RERANK).status()).isEqualTo(StageStatus.DISABLED);
    assertThat(result.chunks()).hasSize(TOP_K);
  }

  /**
   * The role is switched on but was not usable when the run started. Fusion must then never have
   * widened its budget, so the stage can pass the state on untouched and still no more than {@code
   * top-k} chunks reach answer generation.
   */
  @Test
  void aRoleThatWasNotUsableAtTheStartLeavesTopKChunks() {
    RetrievalPipelineResult result = run(WITH_RERANKING, RerankAvailability.NOT_USABLE);

    assertThat(stageOf(result, RetrievalStageName.RANK_FUSION).outgoingCount()).isEqualTo(TOP_K);
    assertThat(stageOf(result, RetrievalStageName.RERANK).status())
        .isEqualTo(StageStatus.UNAVAILABLE);
    assertThat(result.chunks()).hasSize(TOP_K);
  }

  /**
   * The stage's own parameter switches it off even though the role itself is usable - the same
   * untouched-state path, and the same {@code top-k} cap.
   */
  @Test
  void aCandidateCountOfZeroLeavesTopKChunksEvenWithAUsableRole() {
    RetrievalPipelineResult result = run(WITHOUT_RERANKING, RerankAvailability.USABLE);

    assertThat(stageOf(result, RetrievalStageName.RANK_FUSION).outgoingCount()).isEqualTo(TOP_K);
    assertThat(stageOf(result, RetrievalStageName.RERANK).status()).isEqualTo(StageStatus.DISABLED);
    assertThat(result.chunks()).hasSize(TOP_K);
  }

  /**
   * The failure the specification insists must not go unnoticed: the switch is on and the endpoint
   * fails mid-run. The query still answers with top-k chunks, and the protocol says the stage was
   * unavailable rather than pretending it decided something.
   */
  @Test
  void anEndpointThatFailsMidRunCostsTheOrderingNeverTheQuery() {
    when(rerankModelRole.rerank(anyString(), any())).thenReturn(List.of());

    RetrievalPipelineResult result = run(WITH_RERANKING, RerankAvailability.USABLE);

    assertThat(result.chunks()).hasSize(TOP_K);
    assertThat(result.chunks().getFirst().getId()).isEqualTo("c0");
    assertThat(stageOf(result, RetrievalStageName.RERANK).status())
        .isEqualTo(StageStatus.UNAVAILABLE);
  }

  /**
   * Switching the rerank stage off through {@code opaa.query.pipeline.disabled-stages} while the
   * model role stays on must not leave the widened candidate window in place: nothing would restore
   * the {@code top-k} cap, and up to {@code rerankCandidateCount} chunks would reach answer
   * generation.
   */
  @Test
  void switchingTheStageOffThroughThePipelineAlsoTakesTheWidenedBudgetWithIt() {
    RetrievalPipeline withoutRerankStage =
        pipeline(new RetrievalPipelineProperties(Set.of(RetrievalStageName.RERANK)));

    RetrievalPipelineResult result =
        run(withoutRerankStage, WITH_RERANKING, RerankAvailability.USABLE);

    assertThat(stageOf(result, RetrievalStageName.RANK_FUSION).outgoingCount()).isEqualTo(TOP_K);
    assertThat(stageOf(result, RetrievalStageName.RERANK).status()).isEqualTo(StageStatus.DISABLED);
    assertThat(result.chunks()).hasSize(TOP_K);
  }

  /** The stage sits between fusion and document completion, and nowhere else. */
  @Test
  void theStageRunsBetweenFusionAndDocumentCompletion() {
    List<RetrievalStageName> order = pipeline().registeredStages();

    assertThat(order.indexOf(RetrievalStageName.RERANK))
        .isEqualTo(order.indexOf(RetrievalStageName.RANK_FUSION) + 1);
    assertThat(order.indexOf(RetrievalStageName.DOCUMENT_COMPLETION))
        .isEqualTo(order.indexOf(RetrievalStageName.RERANK) + 1);
  }
}
