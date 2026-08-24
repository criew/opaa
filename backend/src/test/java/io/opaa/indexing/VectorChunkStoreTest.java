package io.opaa.indexing;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

/**
 * #838: {@link VectorChunkStore} builds its delete filter via {@link FilterExpressionBuilder}
 * rather than string concatenation - these tests pin the exact {@link Filter.Expression} passed to
 * {@link VectorStore#delete(Filter.Expression)}.
 */
class VectorChunkStoreTest {

  private final VectorStore vectorStore = mock(VectorStore.class);
  private final VectorChunkStore vectorChunkStore = new VectorChunkStore(vectorStore);

  @Test
  void deleteByDocumentIdBuildsAnEqualsFilterOnDocumentId() {
    UUID documentId = UUID.randomUUID();

    vectorChunkStore.deleteByDocumentId(documentId);

    Filter.Expression expected =
        new FilterExpressionBuilder().eq("document_id", documentId.toString()).build();
    verify(vectorStore).delete(expected);
  }

  @Test
  void deleteByLibraryIdBuildsAnEqualsFilterOnLibraryId() {
    UUID libraryId = UUID.randomUUID();

    vectorChunkStore.deleteByLibraryId(libraryId);

    Filter.Expression expected =
        new FilterExpressionBuilder().eq("library_id", libraryId.toString()).build();
    verify(vectorStore).delete(expected);
  }
}
