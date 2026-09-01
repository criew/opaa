package io.opaa.indexing;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

/**
 * #838: {@link VectorChunkStore} builds its delete filter via {@link FilterExpressionBuilder}
 * rather than string concatenation - these tests pin the exact {@link Filter.Expression} passed to
 * {@link VectorStore#delete(Filter.Expression)}. Since #1047, also pins that every write/delete
 * cascades to {@link FullTextChunkStore} - the invariant "a chunk is never vectorized without also
 * being full-text-indexed, and never deleted from one store without the other" this class exists to
 * hold (see its own Javadoc).
 */
class VectorChunkStoreTest {

  private final VectorStore vectorStore = mock(VectorStore.class);
  private final FullTextChunkStore fullTextChunkStore = mock(FullTextChunkStore.class);
  private final VectorChunkStore vectorChunkStore =
      new VectorChunkStore(vectorStore, fullTextChunkStore);

  @Test
  void addChunksWritesToBothStores() {
    List<org.springframework.ai.document.Document> chunks =
        List.of(new org.springframework.ai.document.Document("chunk text"));

    vectorChunkStore.addChunks(chunks);

    InOrder order = inOrder(vectorStore, fullTextChunkStore);
    order.verify(vectorStore).add(chunks);
    order.verify(fullTextChunkStore).indexChunks(chunks);
    verifyNoMoreInteractions(vectorStore, fullTextChunkStore);
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
