package io.opaa.indexing;

import java.util.List;
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
 * library_id}, building delete filters via {@link FilterExpressionBuilder} instead of string
 * concatenation (the same builder {@code QueryService#libraryFilter} already uses for reads).
 *
 * <p>{@link #DOCUMENT_ID_METADATA_KEY} and {@link #LIBRARY_ID_METADATA_KEY} are the single source
 * of truth for these two chunk metadata keys - {@code FileProcessingService} writes them on every
 * chunk it stores, {@code QueryService} reads them back for the permission-aware search filter, and
 * this class writes/deletes by them.
 *
 * <p>Since #1047 (docs/features/hybrid-retrieval.md, "Arbeitspaket 2a"), {@link #addChunks} also
 * writes {@code chunk_full_text} - the lexical-search counterpart of {@code vector_store} - in the
 * same transaction as the vector write, via {@link VectorStoreWriter}: a chunk's vector write and
 * its full-text index entry are always written together, never one without the other. Embedding
 * happens here, <em>before</em> that transaction opens (see {@link #addChunks}'s own Javadoc for
 * why); {@link VectorStoreWriter} only ever sees already-embedded chunks.
 *
 * <p>Both delete methods deliberately do <em>not</em> share a transaction across the vector and
 * full-text delete (#1047 review, finding 4): both callers of this class that run a delete from a
 * deferred {@code TransactionSynchronization#afterCommit} callback (see {@code
 * LibraryDocumentService#deleteDocument}) would otherwise have a {@code @Transactional} delete
 * method try to participate in a transaction whose physical commit has already happened - Spring
 * still reports {@code TransactionSynchronizationManager#isSynchronizationActive()} as {@code true}
 * at that point, so a fresh transaction is not reliably started. Two independent statements are
 * simpler and no worse here: each is a single-row-set {@code DELETE}, already atomic on its own, and
 * idempotent if only one of the two ever runs.
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

  public VectorChunkStore(
      VectorStore vectorStore,
      EmbeddingModel embeddingModel,
      BatchingStrategy batchingStrategy,
      VectorStoreWriter vectorStoreWriter,
      FullTextChunkStore fullTextChunkStore) {
    this.vectorStore = vectorStore;
    this.embeddingModel = embeddingModel;
    this.batchingStrategy = batchingStrategy;
    this.vectorStoreWriter = vectorStoreWriter;
    this.fullTextChunkStore = fullTextChunkStore;
  }

  /**
   * Embeds {@code chunks} on the calling thread (a network call to the configured embedding
   * endpoint - the same {@link EmbeddingModel}/{@link BatchingStrategy} beans {@code PgVectorStore}
   * itself uses, so batching is unchanged from before #1047), then hands the already-embedded
   * chunks to {@link VectorStoreWriter#writeEmbeddedChunks}, which writes {@code vector_store} and
   * {@code chunk_full_text} together in one transaction (docs/features/hybrid-retrieval.md,
   * "Arbeitspaket 2": "Der Volltextindex entsteht beim Schreiben des Chunks, in derselben
   * Transaktion wie Text und Vektor"). Deliberately two steps rather than one {@code
   * VectorStore#add} call inside a transaction: embedding is an HTTP round trip, and holding a
   * pooled database connection for its duration - as a transaction spanning the whole call would -
   * risks exhausting the connection pool under concurrent writes (#1047 review, finding 3).
   */
  public void addChunks(List<Document> chunks) {
    if (chunks.isEmpty()) {
      return;
    }
    List<float[]> embeddings =
        embeddingModel.embed(chunks, EmbeddingOptions.builder().build(), batchingStrategy);
    vectorStoreWriter.writeEmbeddedChunks(chunks, embeddings);
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
