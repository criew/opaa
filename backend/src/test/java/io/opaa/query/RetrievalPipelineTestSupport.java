package io.opaa.query;

import static org.mockito.Mockito.mock;

import io.opaa.llm.RerankModelRole;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * Builds the real {@link RetrievalPipeline} - the one {@link QueryConfiguration} wires - for tests
 * outside {@code io.opaa.query}, whose stage classes are package-private.
 *
 * <p>Exists so a test of a pipeline <b>caller</b> (the administration's diagnosis) can assert what
 * the pipeline actually did rather than what the caller asked a mocked pipeline to do: a mock would
 * happily accept a context built with the wrong parameters, which is exactly the defect this
 * support class's users guard against.
 */
public final class RetrievalPipelineTestSupport {

  private RetrievalPipelineTestSupport() {}

  /**
   * The full pipeline over one stubbed vector store and one rerank role. Decomposition and the
   * lexical path are mocked out: a caller test is about the parameters the pipeline is run with,
   * not about how the candidates were found.
   */
  public static RetrievalPipeline vectorSearchPipeline(
      VectorStore vectorStore, RerankModelRole rerankModelRole) {
    return new QueryConfiguration()
        .retrievalPipeline(
            new SearchScopeStage(),
            new SubQueryDecompositionStage(mock(QueryDecompositionService.class)),
            new VectorSearchStage(vectorStore),
            new FullTextSearchStage(mock(FullTextChunkSearch.class)),
            new MmrSelectionStage(mock(ChunkEmbeddingLookup.class)),
            new RankFusionStage(),
            new RerankStage(rerankModelRole),
            new DocumentCompletionStage(),
            RetrievalPipelineProperties.allStagesEnabled());
  }
}
