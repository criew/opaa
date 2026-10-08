package io.opaa.indexing.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.format.chunk.ChunkMetadataKeys;
import io.opaa.metadata.EmbeddingRateEstimator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

/**
 * {@link VectorChunkStore} builds its delete filter via {@link FilterExpressionBuilder} rather than
 * string concatenation - these tests pin the exact {@link Filter.Expression} passed to {@link
 * VectorStore#delete(Filter.Expression)}. Also pins that {@link VectorChunkStore#addChunks} embeds
 * before handing the result to {@link VectorStoreWriter} (never calling {@link VectorStore#add}
 * directly - see both classes' own Javadoc for why) and that both delete methods cascade to {@link
 * FullTextChunkStore}.
 */
class VectorChunkStoreTest {

  private final VectorStore vectorStore = mock(VectorStore.class);
  private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
  private final BatchingStrategy batchingStrategy = mock(BatchingStrategy.class);
  private final VectorStoreWriter vectorStoreWriter = mock(VectorStoreWriter.class);
  private final FullTextChunkStore fullTextChunkStore = mock(FullTextChunkStore.class);
  private final VectorChunkStore vectorChunkStore =
      new VectorChunkStore(
          vectorStore,
          embeddingModel,
          batchingStrategy,
          vectorStoreWriter,
          fullTextChunkStore,
          new EmbeddingRateEstimator(4.0));

  @Test
  void
      addChunksEmbedsFirstThenHandsTheResultToVectorStoreWriterWithoutTouchingVectorStoreDirectly() {
    List<Document> chunks = List.of(new Document("chunk text"));
    List<float[]> embeddings = List.of(new float[] {0.1f, 0.2f});
    when(embeddingModel.embed(eq(chunks), any(EmbeddingOptions.class), eq(batchingStrategy)))
        .thenReturn(embeddings);

    vectorChunkStore.addChunks(chunks);

    verify(vectorStoreWriter).writeEmbeddedChunks(chunks, embeddings);
    verifyNoInteractions(vectorStore);
  }

  @Test
  void aChunkBeyondTheTokenBudgetIsNamedByItsFundortAndNothingIsWritten() {
    // regression guard for #2328: the batching strategy's bare IllegalArgumentException said
    // neither which chunk nor why; the document's failure must name the chunk.
    VectorChunkStore store = storeWithTheProductionBatchingStrategy();
    List<Document> chunks =
        List.of(
            chunk("Kurzer Abschnitt.", "S. 1", 0),
            chunk("漢字".repeat(6_000), "Abschn. Anlage 3", 1),
            chunk("Noch ein kurzer Abschnitt.", "S. 9", 2));

    assertThatThrownBy(() -> store.addChunks(chunks))
        .isInstanceOfSatisfying(
            ChunkNotEmbeddableException.class,
            e -> {
              assertThat(e.location()).isEqualTo("Abschn. Anlage 3");
              assertThat(e.chunkIndex()).isEqualTo(1);
            });
    verifyNoInteractions(vectorStoreWriter);
  }

  @Test
  void anArgumentErrorNoSingleChunkCausesKeepsItsOwnException() {
    IllegalArgumentException unrelated = new IllegalArgumentException("unsupported options");
    when(embeddingModel.embed(anyList(), any(EmbeddingOptions.class), eq(batchingStrategy)))
        .thenThrow(unrelated);
    when(batchingStrategy.batch(anyList()))
        .thenAnswer(
            call -> {
              List<Document> documents = call.getArgument(0);
              return List.of(documents);
            });

    assertThatThrownBy(() -> vectorChunkStore.addChunks(List.of(new Document("chunk text"))))
        .isSameAs(unrelated);
  }

  private VectorChunkStore storeWithTheProductionBatchingStrategy() {
    when(embeddingModel.embed(anyList(), any(EmbeddingOptions.class), any())).thenCallRealMethod();
    return new VectorChunkStore(
        vectorStore,
        embeddingModel,
        new TokenCountBatchingStrategy(),
        vectorStoreWriter,
        fullTextChunkStore,
        new EmbeddingRateEstimator(4.0));
  }

  private static Document chunk(String text, String location, int index) {
    return new Document(
        text,
        Map.of(
            ChunkMetadataKeys.LOCATION_METADATA_KEY,
            location,
            VectorChunkStore.CHUNK_INDEX_METADATA_KEY,
            index));
  }

  @Test
  void addChunksOfAnEmptyListIsANoOp() {
    vectorChunkStore.addChunks(List.of());

    verifyNoInteractions(embeddingModel, vectorStoreWriter);
  }

  @Test
  void deleteByDocumentIdBuildsAnEqualsFilterOnDocumentIdAndCascadesToFullText() {
    UUID documentId = UUID.randomUUID();

    vectorChunkStore.deleteByDocumentId(documentId);

    Filter.Expression expected =
        new FilterExpressionBuilder().eq("document_id", documentId.toString()).build();
    verify(vectorStore).delete(expected);
    verify(fullTextChunkStore).deleteByDocumentId(documentId);
  }

  @Test
  void deleteByLibraryIdBuildsAnEqualsFilterOnLibraryIdAndCascadesToFullText() {
    UUID libraryId = UUID.randomUUID();

    vectorChunkStore.deleteByLibraryId(libraryId);

    Filter.Expression expected =
        new FilterExpressionBuilder().eq("library_id", libraryId.toString()).build();
    verify(vectorStore).delete(expected);
    verify(fullTextChunkStore).deleteByLibraryId(libraryId);
  }
}
