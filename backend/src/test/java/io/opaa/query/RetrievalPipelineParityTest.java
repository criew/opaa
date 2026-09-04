package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.indexing.metadata.DocumentTypeVocabularyRepository;
import io.opaa.indexing.metadata.MetadataFilter;
import io.opaa.llm.RerankModelRole;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * Proves the staged pipeline (issue #1046) selects exactly what the orchestrator it replaced
 * selected - the behavioural-neutrality claim of docs/features/hybrid-retrieval.md, Arbeitspaket 1,
 * "Was das Refactoring ausdrücklich nicht tut".
 *
 * <p>The reference below is the pre-refactoring {@code QueryService#retrieveAndSelect}, copied
 * verbatim rather than reimplemented, including its two branches: the single-query path that
 * skipped fusion entirely and the multi-sub-query path. The retrieval benchmark measures only the
 * first of them (its harness runs with decomposition switched off), so the multi-sub-query path's
 * neutrality has no measured proof and needs this one.
 */
class RetrievalPipelineParityTest {

  private static final UUID LIBRARY_ID = UUID.randomUUID();

  private final VectorStore vectorStore = mock(VectorStore.class);
  private final ChunkEmbeddingLookup chunkEmbeddingLookup = mock(ChunkEmbeddingLookup.class);
  private final QueryDecompositionService queryDecompositionService =
      mock(QueryDecompositionService.class);

  /** The pipeline as the application wires it, over the mocks of this class. */
  private RetrievalPipeline pipeline() {
    return new QueryConfiguration()
        .retrievalPipeline(
            new SearchScopeStage(),
            new MetadataFilterStage(mock(DocumentTypeVocabularyRepository.class)),
            new SubQueryDecompositionStage(queryDecompositionService),
            new VectorSearchStage(vectorStore),
            // The lexical path is switched off in every QueryProperties this class builds
            // (fullTextSearchEnabled = false): parity is claimed against the pre-#1046 algorithm,
            // which had no second search path. Its own behaviour is covered by
            // FullTextSearchStageTest and FullTextChunkSearchIntegrationTest.
            new FullTextSearchStage(
                mock(FullTextChunkSearch.class), mock(FullTextIndexCompleteness.class)),
            new MmrSelectionStage(chunkEmbeddingLookup),
            new RankFusionStage(),
            new RerankStage(mock(RerankModelRole.class)),
            new DocumentCompletionStage(),
            RetrievalPipelineProperties.allStagesEnabled());
  }

  /**
   * {@code QueryService#retrieveAndSelect} as it stood before issue #1046, with the vector store
   * calls replaced by the pre-computed per-query candidate lists. Kept as literal as possible: this
   * is a reference implementation, not a tidier restatement.
   */
  private static List<Document> preRefactoringSelection(
      List<List<Document>> candidatesPerSearchQuery,
      QueryProperties properties,
      Map<String, float[]> embeddings) {
    if (candidatesPerSearchQuery.size() == 1) {
      List<Document> candidates = candidatesPerSearchQuery.get(0);
      List<Document> selection =
          MmrSelector.select(
              candidates,
              properties.topK(),
              properties.mmrLambda(),
              properties.mmrLambda() >= 1.0 ? Map.of() : embeddings);
      return DocumentCompletion.complete(
          selection, candidates, properties.maxChunksPerDocument(), properties.topK());
    }

    List<Document> pooledCandidates =
        candidatesPerSearchQuery.stream().flatMap(List::stream).toList();
    Map<String, float[]> sharedEmbeddings = properties.mmrLambda() >= 1.0 ? Map.of() : embeddings;
    List<List<Document>> rankedResultsPerSubQuery = new ArrayList<>();
    for (List<Document> candidates : candidatesPerSearchQuery) {
      rankedResultsPerSubQuery.add(
          MmrSelector.select(
              candidates, properties.topK(), properties.mmrLambda(), sharedEmbeddings));
    }
    List<Document> fused = ReciprocalRankFusion.fuse(rankedResultsPerSubQuery, properties.topK());
    return DocumentCompletion.complete(
        fused, pooledCandidates, properties.maxChunksPerDocument(), properties.topK());
  }

  private List<Document> pipelineSelection(
      List<String> searchQueries,
      List<List<Document>> candidatesPerSearchQuery,
      QueryProperties properties) {
    when(queryDecompositionService.decompose(any(), any(), any(Integer.class)))
        .thenReturn(searchQueries);
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenAnswer(
            invocation -> {
              SearchRequest request = invocation.getArgument(0);
              int index = searchQueries.indexOf(request.getQuery());
              return candidatesPerSearchQuery.get(index);
            });
    return pipeline()
        .run(
            new RetrievalContext(
                "Frage",
                List.of(),
                Set.of(LIBRARY_ID),
                MetadataFilter.NONE,
                properties,
                RerankAvailability.SWITCHED_OFF))
        .chunks();
  }

  private static Document chunk(String id, String documentId, double score) {
    return Document.builder()
        .id(id)
        .text(id)
        .metadata(Map.of("document_id", documentId, "file_name", documentId + ".md"))
        .score(score)
        .build();
  }

  /**
   * Randomized rather than hand-picked: the interesting cases of this pipeline are the ones where
   * fusion, the per-document cap and both eviction tiers interact, and those are more reliably hit
   * by sampling many candidate constellations than by inventing a handful. A fixed seed keeps every
   * run identical - a flaky parity test would be worthless as a neutrality proof.
   */
  private static List<List<Document>> randomCandidateLists(Random random, int listCount) {
    List<List<Document>> lists = new ArrayList<>(listCount);
    for (int list = 0; list < listCount; list++) {
      int size = 6 + random.nextInt(10);
      List<Document> candidates = new ArrayList<>(size);
      List<Double> scores = new ArrayList<>();
      for (int i = 0; i < size; i++) {
        scores.add(0.3 + random.nextDouble() * 0.7);
      }
      scores.sort((a, b) -> Double.compare(b, a));
      for (int i = 0; i < size; i++) {
        // Few documents, many chunks: exactly the constellation document completion acts on.
        int documentIndex = random.nextInt(4);
        int chunkIndex = random.nextInt(3);
        candidates.add(
            chunk(
                "doc-" + documentIndex + "#" + chunkIndex, "doc-" + documentIndex, scores.get(i)));
      }
      lists.add(dedupeById(candidates));
    }
    return lists;
  }

  /**
   * One search never returns the same chunk twice; the generator above may, so it is fixed here.
   */
  private static List<Document> dedupeById(List<Document> candidates) {
    Map<String, Document> byId = new HashMap<>();
    List<Document> distinct = new ArrayList<>();
    for (Document candidate : candidates) {
      if (byId.putIfAbsent(candidate.getId(), candidate) == null) {
        distinct.add(candidate);
      }
    }
    return distinct;
  }

  /**
   * {@code top-k} and {@code max-chunks-per-document} vary per run, deliberately down to values the
   * candidate lists overflow: with a budget the selection never reaches, document completion and
   * both eviction tiers are dead code and the comparison would prove nothing about them.
   */
  private static QueryProperties randomProperties(Random random, double mmrLambda) {
    return new QueryProperties(
        2 + random.nextInt(5), 25, mmrLambda, 0.3, 1.0, true, 3, 1 + random.nextInt(3), false, 50);
  }

  @Test
  void singleSearchQueryPathSelectsExactlyWhatTheOrchestratorSelected() {
    Random random = new Random(20260901L);

    for (int run = 0; run < 200; run++) {
      QueryProperties properties = randomProperties(random, 1.0);
      List<List<Document>> lists = randomCandidateLists(random, 1);
      List<Document> expected = preRefactoringSelection(lists, properties, Map.of());

      List<Document> actual = pipelineSelection(List.of("q1"), lists, properties);

      assertThat(actual).as("run %d", run).containsExactlyElementsOf(expected);
    }
  }

  @Test
  void multipleSubQueryPathSelectsExactlyWhatTheOrchestratorSelected() {
    Random random = new Random(20260902L);

    for (int run = 0; run < 200; run++) {
      QueryProperties properties = randomProperties(random, 1.0);
      int listCount = 2 + random.nextInt(2);
      List<List<Document>> lists = randomCandidateLists(random, listCount);
      List<String> searchQueries = new ArrayList<>();
      for (int i = 0; i < listCount; i++) {
        searchQueries.add("q" + i);
      }
      List<Document> expected = preRefactoringSelection(lists, properties, Map.of());

      List<Document> actual = pipelineSelection(searchQueries, lists, properties);

      assertThat(actual).as("run %d", run).containsExactlyElementsOf(expected);
    }
  }

  /**
   * The same equality with diversity selection live - the one configuration in which the pipeline
   * reads chunk embeddings back, and in which the per-list narrowing is more than a truncation.
   */
  @Test
  void diversitySelectionPathSelectsExactlyWhatTheOrchestratorSelected() {
    Random random = new Random(20260903L);

    for (int run = 0; run < 100; run++) {
      QueryProperties properties = randomProperties(random, 0.7);
      int listCount = 1 + random.nextInt(3);
      List<List<Document>> lists = randomCandidateLists(random, listCount);
      List<String> searchQueries = new ArrayList<>();
      for (int i = 0; i < listCount; i++) {
        searchQueries.add("q" + i);
      }
      Map<String, float[]> embeddings = new HashMap<>();
      lists.forEach(
          list ->
              list.forEach(
                  candidate ->
                      embeddings.computeIfAbsent(
                          candidate.getId(),
                          id ->
                              new float[] {
                                random.nextFloat(), random.nextFloat(), random.nextFloat()
                              })));
      when(chunkEmbeddingLookup.findByIds(any())).thenReturn(embeddings);
      List<Document> expected = preRefactoringSelection(lists, properties, embeddings);

      List<Document> actual = pipelineSelection(searchQueries, lists, properties);

      assertThat(actual).as("run %d", run).containsExactlyElementsOf(expected);
    }
  }
}
