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
   * The PostgreSQL text-search configuration every {@code content_tsv} value is built with (docs/
   * features/hybrid-retrieval.md, "Arbeitspaket 2: Der lexikalische Suchpfad" - German stemming and
   * stopwords). {@link FullTextBackfillService} uses the same constant, so a backfilled chunk's
   * {@code content_tsv} is byte-identical to one written on the ingest path. Public so the lexical
   * search path ({@code io.opaa.query.FullTextChunkSearch}) builds a matching {@code
   * to_tsquery(TEXT_SEARCH_CONFIGURATION, ...)} call instead of hardcoding {@code "german"}
   * independently - a second, drifting copy of this value is exactly the kind of mismatch that
   * would silently break matching.
   *
   * <p>No compound splitting is layered on top of it, deliberately: the specification makes that an
   * outcome of the benchmark's {@code compound_word} segment, not a precaution taken in advance.
   */
  public static final String TEXT_SEARCH_CONFIGURATION = "german";

  /**
   * The {@code content_tsv} weight the undecomposed identifier lexemes carry. {@code A} is the
   * highest of PostgreSQL's four weights ({@code ts_rank}'s default weights are {@code {D, C, B, A}
   * = {0.1, 0.2, 0.4, 1.0}}), and body text carries the default {@code D}: a chunk that matches the
   * exact identifier therefore outranks one that merely matches the bare number the German analysis
   * chain left behind - which is what keeps "§ 34" and "§ 35" apart in the ranking and not only in
   * the match.
   */
  static final String IDENTIFIER_LEXEME_WEIGHT = "A";

  /**
   * The {@code content_tsv_version} every row written by {@link #indexChunks} carries. <b>Raise it
   * whenever the lexemes this class stores change</b> - version 2 added the undecomposed identifier
   * lexemes of {@link FullTextIdentifiers} alongside the German analysis chain's output, version 3
   * widened that class's pattern list (keyword-free file numbers, paragraph enumerations). A row
   * written under an older version carries different lexemes and would silently miss the queries
   * the new ones answer, so {@link FullTextBackfillService#backfillBatch} treats it exactly like a
   * missing row - the version scaffolding #1047 put in place for this. Nothing else has to happen
   * for existing rows to be brought up to date: {@link FullTextBackfillScheduler} re-checks the
   * backlog after every process restart, which a deployment carrying a new value of this constant
   * necessarily is. Version 4 (#1130 Befund 1) added the undecomposed email-address lexeme.
   *
   * <p>Public for the same reason {@link #TEXT_SEARCH_CONFIGURATION} is: the lexical search path
   * ({@code io.opaa.query.FullTextChunkSearch}) must restrict its query to rows built under this
   * version, or it would read a row whose lexemes were built by a different chain.
   */
  public static final short CURRENT_TSV_VERSION = 4;

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
   * path).
   *
   * <p>The stored vector is the German analysis chain's output <b>concatenated with the
   * undecomposed identifier lexemes</b> of {@link FullTextIdentifiers}, weighted {@link
   * #IDENTIFIER_LEXEME_WEIGHT}. The {@code simple} configuration is what keeps them undecomposed:
   * it lowercases and does not stem, and the lexemes are ASCII-alphanumeric by construction, so
   * each one survives as exactly one lexeme - the whole point, since a paragraph reference must
   * survive a chain that would otherwise split it at the {@code §} and stem what is left.
   *
   * <p>Deliberately not {@code array_to_tsvector}, which would be the more direct way to insert
   * lexemes verbatim: it produces a vector without position information, and {@code setweight}
   * writes weights into positions - on a positionless vector it is silently a no-op and every
   * identifier lexeme would rank at the default weight {@code D} like ordinary body text.
   *
   * <p>{@code ON CONFLICT (chunk_id) DO UPDATE} - not {@code DO NOTHING} - makes a repeated call
   * idempotent while still updating a row that already exists at an older {@link
   * #CURRENT_TSV_VERSION}: a chunk id already present here at the current version is overwritten
   * with the same values (a genuine no-op), and one present at an older version is brought up to
   * date. {@code DO NOTHING} would have been wrong here: {@link
   * FullTextBackfillService#backfillBatch} selects rows whose {@code content_tsv_version} does not
   * match {@link #CURRENT_TSV_VERSION} - and that constant does get raised, so {@code DO NOTHING}
   * would make every such row permanently unreachable: selected again on every tick, written
   * nowhere, forever reported as {@code processed}, and {@link FullTextBackfillScheduler} would
   * never see an empty batch to go dormant on. Mirrors the vector upsert {@link
   * VectorStoreWriter#writeEmbeddedChunks} already performs on an {@code id} conflict.
   */
  void indexChunks(List<org.springframework.ai.document.Document> chunks) {
    if (chunks.isEmpty()) {
      return;
    }
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
          ps.setString(5, chunk.getText());
          ps.setString(6, String.join(" ", FullTextIdentifiers.extract(chunk.getText())));
          ps.setShort(7, CURRENT_TSV_VERSION);
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
