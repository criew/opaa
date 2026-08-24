package io.opaa.indexing;

import java.util.UUID;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

/**
 * Deletes chunks from the {@link VectorStore} by {@code document_id} or {@code library_id},
 * building the filter via {@link FilterExpressionBuilder} instead of string concatenation (the same
 * builder {@code QueryService#libraryFilter} already uses for reads).
 *
 * <p>{@link #DOCUMENT_ID_METADATA_KEY} and {@link #LIBRARY_ID_METADATA_KEY} are the single source
 * of truth for these two chunk metadata keys - {@code FileProcessingService} writes them on every
 * chunk it stores, {@code QueryService} reads them back for the permission-aware search filter, and
 * this class deletes by them.
 */
@Component
public class VectorChunkStore {

  public static final String DOCUMENT_ID_METADATA_KEY = "document_id";
  public static final String LIBRARY_ID_METADATA_KEY = "library_id";

  private final VectorStore vectorStore;

  public VectorChunkStore(VectorStore vectorStore) {
    this.vectorStore = vectorStore;
  }

  /** Deletes every chunk carrying the given {@code document_id} metadata. */
  public void deleteByDocumentId(UUID documentId) {
    vectorStore.delete(equalsFilter(DOCUMENT_ID_METADATA_KEY, documentId));
  }

  /** Deletes every chunk carrying the given {@code library_id} metadata. */
  public void deleteByLibraryId(UUID libraryId) {
    vectorStore.delete(equalsFilter(LIBRARY_ID_METADATA_KEY, libraryId));
  }

  private Filter.Expression equalsFilter(String metadataKey, UUID value) {
    return new FilterExpressionBuilder().eq(metadataKey, value.toString()).build();
  }
}
