package io.opaa.indexing;

import java.util.List;
import java.util.UUID;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes chunks to the {@link VectorStore} and deletes them by {@code document_id} or {@code
 * library_id}, building delete filters via {@link FilterExpressionBuilder} instead of string
 * concatenation (the same builder {@code QueryService#libraryFilter} already uses for reads).
 *
 * <p>{@link #DOCUMENT_ID_METADATA_KEY} and {@link #LIBRARY_ID_METADATA_KEY} are the single source
 * of truth for these two chunk metadata keys - {@code FileProcessingService} writes them on every
 * chunk it stores, {@code QueryService} reads them back for the permission-aware search filter, and
 * this class writes/deletes by them.
 *
 * <p>Since #1047 (docs/features/hybrid-retrieval.md, "Arbeitspaket 2a"), this class also owns
 * {@code chunk_full_text} - the lexical-search counterpart of {@code vector_store} - via {@link
 * FullTextChunkStore}, so that a chunk's vector write and its full-text index entry are always
 * written and deleted together, atomically ({@link #addChunks} and both delete methods are {@link
 * Transactional}). {@link FullTextChunkStore} is never called directly by anything outside this
 * class for that reason.
 */
@Component
public class VectorChunkStore {

  public static final String DOCUMENT_ID_METADATA_KEY = "document_id";
  public static final String LIBRARY_ID_METADATA_KEY = "library_id";

  private final VectorStore vectorStore;
  private final FullTextChunkStore fullTextChunkStore;

  public VectorChunkStore(VectorStore vectorStore, FullTextChunkStore fullTextChunkStore) {
    this.vectorStore = vectorStore;
    this.fullTextChunkStore = fullTextChunkStore;
  }

  /**
   * Persists {@code chunks} to the vector store and, in the same transaction, indexes each into
   * {@code chunk_full_text} (docs/features/hybrid-retrieval.md, "Arbeitspaket 2": "Der
   * Volltextindex entsteht beim Schreiben des Chunks, in derselben Transaktion wie Text und
   * Vektor") - a transaction that rolls back leaves neither a vector nor a full-text row behind,
   * never one without the other.
   */
  @Transactional
  public void addChunks(List<org.springframework.ai.document.Document> chunks) {
    vectorStore.add(chunks);
    fullTextChunkStore.indexChunks(chunks);
  }

  /** Deletes every chunk (vector and full-text) carrying the given {@code document_id} metadata. */
  @Transactional
  public void deleteByDocumentId(UUID documentId) {
    vectorStore.delete(equalsFilter(DOCUMENT_ID_METADATA_KEY, documentId));
    fullTextChunkStore.deleteByDocumentId(documentId);
  }

  /** Deletes every chunk (vector and full-text) carrying the given {@code library_id} metadata. */
  @Transactional
  public void deleteByLibraryId(UUID libraryId) {
    vectorStore.delete(equalsFilter(LIBRARY_ID_METADATA_KEY, libraryId));
    fullTextChunkStore.deleteByLibraryId(libraryId);
  }

  private Filter.Expression equalsFilter(String metadataKey, UUID value) {
    return new FilterExpressionBuilder().eq(metadataKey, value.toString()).build();
  }
}
