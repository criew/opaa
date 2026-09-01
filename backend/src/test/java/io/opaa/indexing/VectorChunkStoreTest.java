package io.opaa.indexing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

/**
 * #838: {@link VectorChunkStore} builds its delete filter via {@link FilterExpressionBuilder}
 * rather than string concatenation - these tests pin the exact {@link Filter.Expression} passed to
 * {@link VectorStore#delete(Filter.Expression)}. Since #1047, also pins that {@link
 * VectorChunkStore#addChunks} embeds before handing the result to {@link VectorStoreWriter} (never
 * calling {@link VectorStore#add} directly - see both classes' own Javadoc for why) and that both
 * delete methods cascade to {@link FullTextChunkStore}.
 */
class VectorChunkStoreTest {

  private final VectorStore vectorStore = mock(VectorStore.class);
  private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
  private final BatchingStrategy batchingStrategy = mock(BatchingStrategy.class);
  private final VectorStoreWriter vectorStoreWriter = mock(VectorStoreWriter.class);
  private final FullTextChunkStore fullTextChunkStore = mock(FullTextChunkStore.class);
  private final VectorChunkStore vectorChunkStore =
      new VectorChunkStore(
          vectorStore, embeddingModel, batchingStrategy, vectorStoreWriter, fullTextChunkStore);

  @Test
  void addChunksEmbedsFirstThenHandsTheResultToVectorStoreWriterWithoutTouchingVectorStoreDirectly() {
    List<Document> chunks = List.of(new Document("chunk text"));
    List<float[]> embeddings = List.of(new float[] {0.1f, 0.2f});
    when(embeddingModel.embed(eq(chunks), any(EmbeddingOptions.class), eq(batchingStrategy)))
        .thenReturn(embeddings);

    vectorChunkStore.addChunks(chunks);

    verify(vectorStoreWriter).writeEmbeddedChunks(chunks, embeddings);
    verifyNoInteractions(vectorStore);
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
