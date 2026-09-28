package io.opaa.indexing.document;

/**
 * How {@link DocumentIngestService} batches a document's chunks for embedding - declared here so
 * the service needs no bound configuration type; {@code io.opaa.indexing.IndexingProperties}
 * implements it.
 */
public interface EmbeddingBatching {

  /** The upper bound on chunks sent to the embedding model in one call. */
  int batchSize();

  /** The maximum number of sub-batches one document's chunks are embedded in concurrently. */
  int embeddingConcurrency();
}
