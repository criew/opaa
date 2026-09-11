package io.opaa.query.retrieval.ranking;

import static io.opaa.query.retrieval.RetrievalPipelineTestSupport.chunk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.indexing.metadata.MetadataFilter;
import io.opaa.llm.RerankModelRole;
import io.opaa.query.QueryProperties;
import io.opaa.query.retrieval.RerankAvailability;
import io.opaa.query.retrieval.RetrievalContext;
import io.opaa.query.retrieval.RetrievalPipeline;
import io.opaa.query.retrieval.RetrievalPipelineProperties;
import io.opaa.query.retrieval.RetrievalPipelineTestSupport;
import io.opaa.query.retrieval.RetrievalStageName;
import io.opaa.query.retrieval.search.FullTextChunkSearch;
import io.opaa.query.retrieval.search.QueryDecompositionService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * Document completion after fusion, inside the pipeline it belongs to (docs/handbuch/suche.md,
 * Stufe 9). The completion algorithm itself is {@link DocumentCompletionTest}'s subject.
 */
class DocumentCompletionStageTest {

  private static final UUID LIBRARY_ID = UUID.randomUUID();

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

  /**
   * "Abgeschaltet = Identität": with document completion switched off, the pipeline returns exactly
   * what fusion selected - the same result {@code maxChunksPerDocument = 1} produces, since that is
   * what "this pipeline without that stage" means.
   */
  @Test
  void switchedOffStageIsTheIdentity() {
    List<Document> candidates =
        List.of(
            chunk("a-0", "doc-a", 0.9),
            chunk("b-0", "doc-b", 0.8),
            chunk("c-0", "doc-c", 0.7),
            chunk("a-1", "doc-a", 0.5));
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(candidates);
    QueryProperties completing = new QueryProperties(3, 25, 1.0, 0.3, false, 3, 2, false, 50);
    QueryProperties notCompleting = new QueryProperties(3, 25, 1.0, 0.3, false, 3, 1, false, 50);
    RetrievalContext completingRun =
        new RetrievalContext(
            "Frage",
            List.of(),
            Set.of(LIBRARY_ID),
            MetadataFilter.NONE,
            completing,
            RerankAvailability.SWITCHED_OFF);
    RetrievalContext notCompletingRun =
        new RetrievalContext(
            "Frage",
            List.of(),
            Set.of(LIBRARY_ID),
            MetadataFilter.NONE,
            notCompleting,
            RerankAvailability.SWITCHED_OFF);

    List<Document> stageSwitchedOff =
        pipeline(new RetrievalPipelineProperties(Set.of(RetrievalStageName.DOCUMENT_COMPLETION)))
            .run(completingRun)
            .chunks();
    List<Document> stageNeutralizedByParameter =
        pipeline(RetrievalPipelineProperties.allStagesEnabled()).run(notCompletingRun).chunks();
    List<Document> stageActive =
        pipeline(RetrievalPipelineProperties.allStagesEnabled()).run(completingRun).chunks();

    assertThat(stageSwitchedOff).containsExactlyElementsOf(stageNeutralizedByParameter);
    // The scenario is one where the stage genuinely does something - otherwise the assertion above
    // would hold for a switch that never took effect.
    assertThat(stageActive).isNotEqualTo(stageSwitchedOff);
    assertThat(stageActive).extracting(Document::getId).contains("a-1");
  }
}
