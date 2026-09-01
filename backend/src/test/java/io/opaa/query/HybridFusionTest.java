package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.llm.RerankModelRole;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * The behaviour change of #1049 at the level it is claimed on (docs/features/hybrid-retrieval.md,
 * Arbeitspaket 3): the whole pipeline, both search paths wired, one fusion over both.
 *
 * <p>Deliberately separate from {@link RetrievalPipelineTest}, which pins the structural guarantees
 * of the staged pipeline over the vector path alone and switches the lexical path off for exactly
 * that reason.
 */
class HybridFusionTest {

  private static final UUID LIBRARY_ID = UUID.randomUUID();
  private static final QueryProperties HYBRID =
      new QueryProperties(8, 25, 1.0, 0.3, 1.0, false, 3, 1, true, 50);
  private static final QueryProperties VECTOR_ONLY =
      new QueryProperties(8, 25, 1.0, 0.3, 1.0, false, 3, 1, false, 50);

  private final VectorStore vectorStore = mock(VectorStore.class);
  private final ChunkEmbeddingLookup chunkEmbeddingLookup = mock(ChunkEmbeddingLookup.class);
  private final QueryDecompositionService queryDecompositionService =
      mock(QueryDecompositionService.class);
  private final FullTextChunkSearch fullTextChunkSearch = mock(FullTextChunkSearch.class);
  private final FullTextBackfillGate backfillGate = mock(FullTextBackfillGate.class);

  private RetrievalPipeline pipeline() {
    return new QueryConfiguration()
        .retrievalPipeline(
            new SearchScopeStage(),
            new SubQueryDecompositionStage(queryDecompositionService),
            new VectorSearchStage(vectorStore),
            new FullTextSearchStage(fullTextChunkSearch, backfillGate),
            new MmrSelectionStage(chunkEmbeddingLookup),
            new RankFusionStage(),
            new RerankStage(mock(RerankModelRole.class)),
            new DocumentCompletionStage(),
            RetrievalPipelineProperties.allStagesEnabled());
  }

  private static Document chunk(String id, double score) {
    return Document.builder()
        .id(id)
        .text(id)
        .metadata(Map.of("document_id", "doc-" + id, "file_name", id + ".md"))
        .score(score)
        .build();
  }

  private List<Document> run(QueryProperties properties) {
    return pipeline()
        .run(new RetrievalContext("Frage", List.of(), Set.of(LIBRARY_ID), properties))
        .chunks();
  }

  /**
   * The #938 failure class in miniature: the chunk that carries the searched term literally is
   * outside the vector path's window and only the lexical path finds it. Without the fusion it
   * cannot reach the answer at all; with it, it does.
   */
  @Test
  void aChunkOnlyTheLexicalPathFoundReachesTheSelection() {
    when(backfillGate.searchableLibraries(Set.of(LIBRARY_ID))).thenReturn(Set.of(LIBRARY_ID));
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(chunk("vector-a", 0.8), chunk("vector-b", 0.7)));
    when(fullTextChunkSearch.search(anyString(), any(), anyInt()))
        .thenReturn(List.of(chunk("literal-term", 0.09)));

    assertThat(run(HYBRID)).extracting(Document::getId).contains("literal-term");
    assertThat(run(VECTOR_ONLY)).extracting(Document::getId).doesNotContain("literal-term");
  }

  /**
   * Dedupliziert wird per Chunk-Kennung, nie per Score: a chunk both paths return is one candidate
   * with two contributions - so it wins against each path's own top hit, and it appears exactly
   * once.
   */
  @Test
  void aChunkBothPathsFoundIsOneCandidateWithTwoContributions() {
    when(backfillGate.searchableLibraries(Set.of(LIBRARY_ID))).thenReturn(Set.of(LIBRARY_ID));
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(chunk("vector-top", 0.8), chunk("both", 0.6)));
    when(fullTextChunkSearch.search(anyString(), any(), anyInt()))
        .thenReturn(List.of(chunk("lexical-top", 0.09), chunk("both", 0.05)));

    List<Document> selection = run(HYBRID);

    assertThat(selection)
        .extracting(Document::getId)
        .containsExactly("both", "vector-top", "lexical-top");
    // The surviving instance is the vector path's: its score is a cosine similarity, the lexical
    // path's is a ts_rank, and the two are not comparable quantities (#912).
    assertThat(selection.getFirst().getScore()).isEqualTo(0.6);
  }

  /**
   * A failing lexical query costs candidates, never the answer - the fusion runs on with what is
   * left, and the result is the vector-only selection.
   */
  @Test
  void aFailingLexicalQueryLeavesTheVectorSelectionIntact() {
    when(backfillGate.searchableLibraries(Set.of(LIBRARY_ID))).thenReturn(Set.of(LIBRARY_ID));
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(chunk("vector-a", 0.8), chunk("vector-b", 0.7)));
    when(fullTextChunkSearch.search(anyString(), any(), anyInt()))
        .thenThrow(new IllegalStateException("relation chunk_full_text does not exist"));

    assertThat(run(HYBRID)).extracting(Document::getId).containsExactly("vector-a", "vector-b");
  }

  /**
   * The backfill gate keeps a library whose full-text index is incomplete out of the lexical path
   * entirely - a half-filled index returns hits and hides the rest
   * (docs/features/hybrid-retrieval.md, Arbeitspaket 2a).
   */
  @Test
  void aLibraryWithAnUnfinishedBackfillContributesNoLexicalCandidates() {
    when(backfillGate.searchableLibraries(Set.of(LIBRARY_ID))).thenReturn(Set.of());
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(chunk("vector-a", 0.8)));

    assertThat(run(HYBRID)).extracting(Document::getId).containsExactly("vector-a");
    verifyNoInteractions(fullTextChunkSearch);
  }
}
