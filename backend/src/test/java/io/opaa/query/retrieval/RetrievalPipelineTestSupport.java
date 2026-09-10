package io.opaa.query.retrieval;

import static org.mockito.Mockito.mock;

import io.opaa.indexing.metadata.DocumentTypeVocabularyRepository;
import io.opaa.indexing.metadata.MetadataFilter;
import io.opaa.llm.RerankModelRole;
import io.opaa.query.QueryConfiguration;
import io.opaa.query.QueryProperties;
import io.opaa.query.retrieval.ranking.ChunkEmbeddingLookup;
import io.opaa.query.retrieval.ranking.DocumentCompletionStage;
import io.opaa.query.retrieval.ranking.MmrSelectionStage;
import io.opaa.query.retrieval.ranking.RankFusionStage;
import io.opaa.query.retrieval.ranking.RerankStage;
import io.opaa.query.retrieval.scope.MetadataFilterStage;
import io.opaa.query.retrieval.scope.SearchScopeStage;
import io.opaa.query.retrieval.search.FullTextChunkSearch;
import io.opaa.query.retrieval.search.FullTextIndexCompleteness;
import io.opaa.query.retrieval.search.FullTextSearchStage;
import io.opaa.query.retrieval.search.QueryDecompositionService;
import io.opaa.query.retrieval.search.SubQueryDecompositionStage;
import io.opaa.query.retrieval.search.VectorSearchStage;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * Builds the real {@link RetrievalPipeline} - the one {@link QueryConfiguration} wires - for tests
 * in the stage packages and outside {@code io.opaa.query}.
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
    return pipeline(
        vectorStore,
        mock(FullTextChunkSearch.class),
        mock(ChunkEmbeddingLookup.class),
        mock(QueryDecompositionService.class),
        rerankModelRole,
        RetrievalPipelineProperties.allStagesEnabled());
  }

  /**
   * The full pipeline over the given collaborators, through {@link
   * QueryConfiguration#retrievalPipeline} itself rather than a second stage list, so a test can
   * never run a different stage order than the application does.
   */
  public static RetrievalPipeline pipeline(
      VectorStore vectorStore,
      FullTextChunkSearch fullTextChunkSearch,
      ChunkEmbeddingLookup chunkEmbeddingLookup,
      QueryDecompositionService queryDecompositionService,
      RerankModelRole rerankModelRole,
      RetrievalPipelineProperties pipelineProperties) {
    return new QueryConfiguration()
        .retrievalPipeline(
            new SearchScopeStage(),
            new MetadataFilterStage(mock(DocumentTypeVocabularyRepository.class)),
            new SubQueryDecompositionStage(queryDecompositionService),
            new VectorSearchStage(vectorStore),
            new FullTextSearchStage(fullTextChunkSearch, mock(FullTextIndexCompleteness.class)),
            new MmrSelectionStage(chunkEmbeddingLookup),
            new RankFusionStage(),
            new RerankStage(rerankModelRole),
            new DocumentCompletionStage(),
            pipelineProperties);
  }

  /** A run over {@code searchScope} without history, metadata filter or reranking. */
  public static RetrievalContext context(Set<UUID> searchScope, QueryProperties properties) {
    return new RetrievalContext(
        "Frage",
        List.of(),
        searchScope,
        MetadataFilter.NONE,
        properties,
        RerankAvailability.SWITCHED_OFF);
  }

  /** A chunk of {@code documentId} with the metadata the stages group and complete by. */
  public static Document chunk(String id, String documentId, double score) {
    return Document.builder()
        .id(id)
        .text(id)
        .metadata(Map.of("document_id", documentId, "file_name", documentId + ".md"))
        .score(score)
        .build();
  }
}
