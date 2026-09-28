/**
 * How a document is taken in: the one ingestion path every source and every upload goes through,
 * {@link io.opaa.indexing.document.DocumentIngestService#ingest} on a {@link
 * io.opaa.indexing.document.DocumentIngest} - parse, chunk, store, mark - and, through {@link
 * io.opaa.indexing.document.AttachmentIndexer}, every attachment found or handed in along the way.
 *
 * <p>The {@code Document} row itself, with its provenance, checksum and status, belongs to the
 * holdings in {@code io.opaa.knowledge}. Uses {@code format} for the format handling, {@code chunk}
 * for cutting and storing, {@code metadata} for the schema fields, {@code attachment} for what a
 * caller hands in, and of {@code job} only the protocol sink and what ends a run. The batching of
 * the embedding comes as {@link io.opaa.indexing.document.EmbeddingBatching}. {@code source} and
 * {@code maintenance} depend on this package, never the other way round.
 */
package io.opaa.indexing.document;
