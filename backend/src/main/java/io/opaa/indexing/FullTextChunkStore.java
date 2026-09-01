package io.opaa.indexing;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes/deletes rows in {@code chunk_full_text} (docs/features/hybrid-retrieval.md, "Arbeitspaket
 * 2a") - the lexical-search counterpart of {@link VectorChunkStore}'s {@code vector_store} writes.
 * A dedicated table, not columns on {@code vector_store} itself: see {@code
 * changes/002-chunk-full-text-index.yaml}'s own comment for why.
 *
 * <p>Never called directly by {@link FileProcessingService} - {@link VectorChunkStore} owns both
 * writes (see {@link VectorChunkStore#addChunks}) so a chunk can never be vectorized without also
 * being full-text-indexed, and both deletes (see {@link VectorChunkStore#deleteByDocumentId}/{@link
 * VectorChunkStore#deleteByLibraryId}) so a full-text row can never outlive the vector chunk it
 * belongs to.
 */
@Component
public class FullTextChunkStore {

  /**
   * The PostgreSQL text-search configuration every {@code content_tsv} value is built with (docs/
   * features/hybrid-retrieval.md, "Arbeitspaket 2: Der lexikalische Suchpfad" - German stemming and
   * stopwords). {@link FullTextBackfillService} uses the same constant, so a backfilled chunk's
   * {@code content_tsv} is byte-identical to one written on the ingest path.
   */
  static final String TEXT_SEARCH_CONFIGURATION = "german";

  private final JdbcTemplate jdbcTemplate;

  public FullTextChunkStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Inserts one {@code chunk_full_text} row per chunk, keyed by {@link
   * org.springframework.ai.document.Document#getId()} - the same id {@link
   * org.springframework.ai.vectorstore.VectorStore#add} persists as {@code vector_store.id}. Each
   * chunk must already carry {@link VectorChunkStore#DOCUMENT_ID_METADATA_KEY}/{@link
   * VectorChunkStore#LIBRARY_ID_METADATA_KEY} metadata (see {@code
   * FileProcessingService#storeChunks} - every caller of this method already goes through that
   * path). {@code ON CONFLICT DO NOTHING} makes a repeated call idempotent, matching {@link
   * FullTextBackfillService}'s own idempotency contract - a chunk id already present here (e.g.
   * inserted by a concurrently running backfill batch) is left untouched rather than raising a
   * primary-key violation.
   */
  void indexChunks(List<org.springframework.ai.document.Document> chunks) {
    if (chunks.isEmpty()) {
      return;
    }
    jdbcTemplate.batchUpdate(
        "INSERT INTO chunk_full_text (chunk_id, document_id, library_id, content_tsv) "
            + "VALUES (?, ?, ?, to_tsvector(?::regconfig, ?)) ON CONFLICT (chunk_id) DO NOTHING",
        chunks,
        chunks.size(),
        (ps, chunk) -> {
          ps.setObject(1, UUID.fromString(chunk.getId()));
          ps.setObject(
              2,
              UUID.fromString(
                  (String) chunk.getMetadata().get(VectorChunkStore.DOCUMENT_ID_METADATA_KEY)));
          ps.setObject(
              3,
              UUID.fromString(
                  (String) chunk.getMetadata().get(VectorChunkStore.LIBRARY_ID_METADATA_KEY)));
          ps.setString(4, TEXT_SEARCH_CONFIGURATION);
          ps.setString(5, chunk.getText());
        });
  }

  /**
   * Deletes every {@code chunk_full_text} row for {@code documentId} - mirrors {@link
   * VectorChunkStore#deleteByDocumentId}.
   */
  void deleteByDocumentId(UUID documentId) {
    jdbcTemplate.update("DELETE FROM chunk_full_text WHERE document_id = ?", documentId);
  }

  /**
   * Deletes every {@code chunk_full_text} row for {@code libraryId} - mirrors {@link
   * VectorChunkStore#deleteByLibraryId}.
   */
  void deleteByLibraryId(UUID libraryId) {
    jdbcTemplate.update("DELETE FROM chunk_full_text WHERE library_id = ?", libraryId);
  }
}
