package io.opaa.indexing;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

/**
 * Writes chunks to the {@link VectorStore} and deletes them by {@code document_id} or {@code
 * library_id}. {@link #DOCUMENT_ID_METADATA_KEY} and {@link #LIBRARY_ID_METADATA_KEY} are the
 * single source of truth for those two chunk metadata keys. {@link #addChunks} also writes {@code
 * chunk_full_text} in the same transaction as the vector write, via {@link VectorStoreWriter} -
 * never one without the other; embedding happens before that transaction opens.
 *
 * <p>The delete methods deliberately do not share a transaction across the two stores: a caller
 * deleting from {@code afterCommit} cannot reliably start a fresh one, and each delete is a single
 * atomic statement, idempotent if only one of the two ever runs.
 */
@Component
public class VectorChunkStore {

  public static final String DOCUMENT_ID_METADATA_KEY = "document_id";
  public static final String LIBRARY_ID_METADATA_KEY = "library_id";

  private final VectorStore vectorStore;
  private final EmbeddingModel embeddingModel;
  private final BatchingStrategy batchingStrategy;
  private final VectorStoreWriter vectorStoreWriter;
  private final FullTextChunkStore fullTextChunkStore;
  private final EmbeddingRateEstimator embeddingRateEstimator;

  public VectorChunkStore(
      VectorStore vectorStore,
      EmbeddingModel embeddingModel,
      BatchingStrategy batchingStrategy,
      VectorStoreWriter vectorStoreWriter,
      FullTextChunkStore fullTextChunkStore,
      EmbeddingRateEstimator embeddingRateEstimator) {
    this.vectorStore = vectorStore;
    this.embeddingModel = embeddingModel;
    this.batchingStrategy = batchingStrategy;
    this.vectorStoreWriter = vectorStoreWriter;
    this.fullTextChunkStore = fullTextChunkStore;
    this.embeddingRateEstimator = embeddingRateEstimator;
  }

  /**
   * Embeds {@code chunks} on the calling thread - the same {@link EmbeddingModel}/{@link
   * BatchingStrategy} beans {@code PgVectorStore} uses - and only then hands them to {@link
   * VectorStoreWriter#writeEmbeddedChunks}, which writes both stores in one transaction. Two steps
   * rather than one {@code VectorStore#add} inside a transaction: embedding is an HTTP round trip,
   * and holding a pooled connection for its duration risks exhausting the pool.
   */
  public void addChunks(List<Document> chunks) {
    if (chunks.isEmpty()) {
      return;
    }
    long startedAt = System.nanoTime();
    List<float[]> embeddings =
        embeddingModel.embed(chunks, EmbeddingOptions.builder().build(), batchingStrategy);
    // Measured around the embedding round trip only, never the write: the Folgekosten estimate
    // names the cost of embedding calls, and a slow disk must not make a reindex look expensive.
    embeddingRateEstimator.record(chunks.size(), System.nanoTime() - startedAt);
    vectorStoreWriter.writeEmbeddedChunks(chunks, embeddings);
  }

  /**
   * Rewrites document-level metadata keys on every chunk of {@code documentId} without touching
   * content or embedding (ADR-0024) - see {@link VectorStoreWriter#updateDocumentMetadata}. The
   * path a metadata correction and the Bestandslauf take instead of re-indexing.
   *
   * @return the number of chunks updated
   */
  public int updateDocumentMetadata(
      UUID documentId, Map<String, Object> values, Set<String> keysToClear) {
    return vectorStoreWriter.updateDocumentMetadata(documentId, values, keysToClear);
  }

  /** Deletes every chunk (vector and full-text) carrying the given {@code document_id} metadata. */
  public void deleteByDocumentId(UUID documentId) {
    vectorStore.delete(equalsFilter(DOCUMENT_ID_METADATA_KEY, documentId));
    fullTextChunkStore.deleteByDocumentId(documentId);
  }

  /** Deletes every chunk (vector and full-text) carrying the given {@code library_id} metadata. */
  public void deleteByLibraryId(UUID libraryId) {
    vectorStore.delete(equalsFilter(LIBRARY_ID_METADATA_KEY, libraryId));
    fullTextChunkStore.deleteByLibraryId(libraryId);
  }

  private Filter.Expression equalsFilter(String metadataKey, UUID value) {
    return new FilterExpressionBuilder().eq(metadataKey, value.toString()).build();
  }
}
