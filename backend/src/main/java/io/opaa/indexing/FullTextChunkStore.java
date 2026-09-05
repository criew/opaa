package io.opaa.indexing;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes/deletes rows in {@code chunk_full_text} (docs/features/hybrid-retrieval.md, "Arbeitspaket
 * 2a") - the lexical-search counterpart of {@link VectorChunkStore}'s {@code vector_store} writes.
 * A dedicated table, not columns on {@code vector_store} itself: see {@code
 * changes/003-chunk-full-text-table.yaml}'s own comment for why.
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
   * The PostgreSQL text-search configuration every {@code content_tsv} value is built with
   * (docs/features/hybrid-retrieval.md, "Arbeitspaket 2"). Public so the lexical search path builds
   * a matching {@code to_tsquery} call instead of hardcoding {@code "german"} independently, which
   * would be exactly the kind of drift that silently breaks matching. No compound splitting is
   * layered on top: the specification makes that an outcome of the benchmark, not a precaution.
   */
  public static final String TEXT_SEARCH_CONFIGURATION = "german";

  /**
   * The {@code content_tsv} weight the undecomposed identifier lexemes carry. {@code A} is the
   * highest of PostgreSQL's four weights and body text carries the default {@code D}, so a chunk
   * matching the exact identifier outranks one matching only the bare number the German analysis
   * chain left behind - which keeps "§ 34" and "§ 35" apart in the ranking, not only in the match.
   */
  static final String IDENTIFIER_LEXEME_WEIGHT = "A";

  /**
   * The {@code content_tsv_version} every row written by {@link #indexChunks} carries. <b>Raise it
   * whenever the lexemes this class stores change</b>: rows at an older version are invisible to
   * the lexical search path, counted as missing by {@link FullTextIndexFillStateService}, and
   * brought up to date only by {@link PipelineReindexService#reindexBatch}, never by a background
   * job. Public because the query path must restrict itself to rows built under this version.
   */
  public static final short CURRENT_TSV_VERSION = 4;

  private final JdbcTemplate jdbcTemplate;

  public FullTextChunkStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Inserts one {@code chunk_full_text} row per chunk, keyed by the id {@code VectorStore#add}
   * persists as {@code vector_store.id}: the German analysis chain's output concatenated with
   * {@link FullTextIdentifiers}' undecomposed lexemes at weight {@link #IDENTIFIER_LEXEME_WEIGHT}.
   * Built with {@code to_tsvector}, never the positionless {@code array_to_tsvector} that would
   * make {@code setweight} a no-op; {@code ON CONFLICT DO UPDATE} brings an older row up to date.
   */
  void indexChunks(List<org.springframework.ai.document.Document> chunks) {
    indexChunks(chunks, null);
  }

  /**
   * {@link #indexChunks(List)} with a document-level supplement appended to the analysed text: the
   * freie Schlagworte of the document (metadata-schema.md, Teil II (c)). The supplement reaches
   * this index only - it is neither stored as chunk text nor written to any chunk metadata key, so
   * no filter can ever name it.
   */
  void indexChunks(List<org.springframework.ai.document.Document> chunks, String supplement) {
    if (chunks.isEmpty()) {
      return;
    }
    String suffix = supplement == null || supplement.isBlank() ? "" : "\n" + supplement;
    jdbcTemplate.batchUpdate(
        "INSERT INTO chunk_full_text (chunk_id, document_id, library_id, content_tsv, "
            + "content_tsv_version) VALUES (?, ?, ?, "
            + "to_tsvector(?::regconfig, ?) || setweight(to_tsvector('simple', ?), '"
            + IDENTIFIER_LEXEME_WEIGHT
            + "'), ?) "
            + "ON CONFLICT (chunk_id) DO UPDATE SET "
            + "content_tsv = EXCLUDED.content_tsv, "
            + "content_tsv_version = EXCLUDED.content_tsv_version",
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
          ps.setString(5, chunk.getText() + suffix);
          ps.setString(6, String.join(" ", FullTextIdentifiers.extract(chunk.getText())));
          ps.setShort(7, CURRENT_TSV_VERSION);
        });
  }

  /**
   * Deletes every {@code chunk_full_text} row for {@code documentId} - mirrors {@link
   * VectorChunkStore#deleteByDocumentId}, which is what keeps that class's stated invariant true: a
   * full-text row can never outlive the vector chunk it belongs to.
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
