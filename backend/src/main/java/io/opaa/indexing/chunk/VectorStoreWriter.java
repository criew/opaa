package io.opaa.indexing.chunk;

import com.pgvector.PGvector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes already-embedded chunks into {@code vector_store} and {@code chunk_full_text} in a single
 * transaction - a plain JDBC upsert mirroring {@code PgVectorStore#doAdd}, not {@link
 * org.springframework.ai.vectorstore.VectorStore#add}, which embeds and writes in one step and
 * would hold the connection for the whole embedding round trip. {@link VectorChunkStore#addChunks}
 * embeds first, outside any transaction, so the connection here is held for a few local inserts.
 * Without that split, concurrent sub-batches could exceed HikariCP's pool and starve the query
 * path.
 *
 * <p>The vector goes to the column {@link EmbeddingIndexState} names: the embedding column, or the
 * target column while the index is stale.
 *
 * <p>Schema and table name come from the same {@code spring.ai.vectorstore.pgvector.*} properties
 * {@code PgVectorStore} binds; {@code id-type} is assumed {@code UUID}, as elsewhere.
 */
@Component
public class VectorStoreWriter {

  /**
   * Namespace of the per-document advisory lock that orders the writers of one document's vector
   * rows - registered with the other namespaces at {@code
   * AssetGrantRepository#ASSET_GRANT_MUTATION_LOCK_NAMESPACE}. Keyed by a hash of the document id;
   * a collision only serializes two documents.
   */
  public static final int DOCUMENT_CHUNK_LOCK_NAMESPACE = 208;

  /**
   * Takes the document lock for the document id bound to its one parameter; uncorrelated, so a
   * {@code WHERE} clause evaluates it once, before it locks the first row. Always {@code true}.
   */
  private static final String DOCUMENT_LOCK_CONDITION =
      "(SELECT true FROM (SELECT pg_advisory_xact_lock("
          + DOCUMENT_CHUNK_LOCK_NAMESPACE
          + ", hashtext(CAST(? AS text)))) acquired)";

  private final JdbcTemplate jdbcTemplate;
  private final FullTextChunkStore fullTextChunkStore;
  private final EmbeddingIndexState indexState;
  private final ObjectMapper objectMapper;
  private final String schemaName;
  private final String tableName;

  public VectorStoreWriter(
      JdbcTemplate jdbcTemplate,
      FullTextChunkStore fullTextChunkStore,
      EmbeddingIndexState indexState,
      ObjectMapper objectMapper,
      @Value("${opaa.database.schema}") String schemaName,
      @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {
    this.jdbcTemplate = jdbcTemplate;
    this.fullTextChunkStore = fullTextChunkStore;
    this.indexState = indexState;
    this.objectMapper = objectMapper;
    this.schemaName = schemaName;
    this.tableName = tableName;
  }

  /**
   * Upserts {@code chunks} (id, content, metadata, embedding - same column layout {@code
   * PgVectorStore} creates) and, in the same transaction, indexes each into {@code chunk_full_text}
   * via {@link FullTextChunkStore#indexChunks}. {@code embeddings} must be positionally aligned
   * with {@code chunks} (index i of one corresponds to index i of the other) - the contract {@link
   * EmbeddingModel#embed(List, EmbeddingOptions, BatchingStrategy)} already guarantees.
   */
  @Transactional
  public void writeEmbeddedChunks(List<Document> chunks, List<float[]> embeddings) {
    if (chunks.isEmpty()) {
      return;
    }
    requireWritableLibraries(chunks);
    // Zipped up front, not looked up per row via chunks.indexOf(chunk) inside the callback below:
    // JdbcTemplate's ParameterizedPreparedStatementSetter only ever hands back the row item itself,
    // never its index, and indexOf would additionally be wrong for two structurally equal Document
    // instances (only the first match would ever be found).
    List<ChunkWithEmbedding> zipped = new ArrayList<>(chunks.size());
    for (int i = 0; i < chunks.size(); i++) {
      zipped.add(new ChunkWithEmbedding(chunks.get(i), embeddings.get(i)));
    }

    indexState.whileWriting(
        column ->
            jdbcTemplate.batchUpdate(
                upsertSql(column),
                zipped,
                zipped.size(),
                (ps, entry) -> {
                  UUID id = UUID.fromString(entry.chunk().getId());
                  String content = entry.chunk().getText();
                  String metadataJson = toJson(entry.chunk().getMetadata());
                  PGvector embedding = new PGvector(entry.embedding());

                  ps.setObject(1, id);
                  ps.setString(2, content);
                  ps.setString(3, metadataJson);
                  ps.setObject(4, embedding);
                  ps.setString(5, content);
                  ps.setString(6, metadataJson);
                  ps.setObject(7, embedding);
                }));
    fullTextChunkStore.indexChunks(chunks);
  }

  /**
   * Writes new vectors for chunks that are already stored, by plain {@code UPDATE}: a chunk whose
   * row is gone, or carries another text than {@code chunks} names, is skipped and never inserted
   * again. Only the chunks whose vector row was updated get their {@code chunk_full_text} row, in
   * the same transaction; the update holds their vector rows locked until commit, and every delete
   * path removes the vector row before the full-text row, so no full-text row outlives its chunk.
   * {@code embeddings} is positionally aligned with {@code chunks}. Content and metadata stay.
   *
   * <p>Lock order: first the document lock of every document the chunks name (sorted), then the
   * vector rows, {@code NOWAIT}. The single-document writers ({@link #deleteChunksOfDocument},
   * {@link #updateDocumentMetadata}) take the same document lock before their first row, so they
   * wait instead of deadlocking; a library-wide delete holding a row fails this write at once
   * instead of deadlocking with it.
   *
   * @return how many chunks were still stored and got their new vector
   * @throws org.springframework.dao.CannotAcquireLockException when another writer holds one of the
   *     vector rows; nothing is written then
   */
  @Transactional
  public int rewriteEmbeddedChunks(List<Document> chunks, List<float[]> embeddings) {
    if (chunks.isEmpty()) {
      return 0;
    }
    chunks.stream()
        .map(chunk -> chunk.getMetadata().get(VectorChunkStore.DOCUMENT_ID_METADATA_KEY))
        .filter(Objects::nonNull)
        .map(Object::toString)
        .distinct()
        .sorted()
        .forEach(this::lockDocumentChunks);
    Set<String> stored =
        new HashSet<>(
            jdbcTemplate.queryForList(
                "SELECT id::text FROM "
                    + schemaName
                    + "."
                    + tableName
                    + " WHERE id = ANY (?::uuid[]) ORDER BY id FOR UPDATE NOWAIT",
                String.class,
                (Object) chunks.stream().map(Document::getId).toArray(String[]::new)));
    List<ChunkWithEmbedding> zipped = new ArrayList<>(chunks.size());
    for (int i = 0; i < chunks.size(); i++) {
      if (stored.contains(chunks.get(i).getId())) {
        zipped.add(new ChunkWithEmbedding(chunks.get(i), embeddings.get(i)));
      }
    }
    if (zipped.isEmpty()) {
      return 0;
    }
    int[][] counts =
        indexState.whileWriting(
            column ->
                jdbcTemplate.batchUpdate(
                    "UPDATE "
                        + schemaName
                        + "."
                        + tableName
                        + " SET "
                        + column
                        + " = ? WHERE id = ? AND content = ?",
                    zipped,
                    zipped.size(),
                    (ps, entry) -> {
                      ps.setObject(1, new PGvector(entry.embedding()));
                      ps.setObject(2, UUID.fromString(entry.chunk().getId()));
                      ps.setString(3, entry.chunk().getText());
                    }));
    List<Document> stillStored = new ArrayList<>(zipped.size());
    int position = 0;
    for (int[] batch : counts) {
      for (int count : batch) {
        if (count > 0) {
          stillStored.add(zipped.get(position).chunk());
        }
        position++;
      }
    }
    fullTextChunkStore.indexChunks(stillStored);
    return stillStored.size();
  }

  /**
   * Deletes the vector rows of {@code documentId} in one statement that first takes the document
   * lock ({@link #DOCUMENT_CHUNK_LOCK_NAMESPACE}) - before its first row, and for the rest of the
   * caller's transaction or, without one, of the statement.
   */
  public void deleteChunksOfDocument(UUID documentId) {
    jdbcTemplate.update(
        "DELETE FROM "
            + schemaName
            + "."
            + tableName
            + " WHERE metadata->>'"
            + VectorChunkStore.DOCUMENT_ID_METADATA_KEY
            + "' = ? AND "
            + DOCUMENT_LOCK_CONDITION,
        documentId.toString(),
        documentId.toString());
  }

  /** Takes the document lock for the rest of the current transaction. */
  private void lockDocumentChunks(String documentId) {
    jdbcTemplate.queryForObject("SELECT " + DOCUMENT_LOCK_CONDITION, Boolean.class, documentId);
  }

  /**
   * The upsert into {@code column}. Into the target column of a stale index, the embedding column
   * keeps its vector of the previous model only while the chunk text stays the same; a new text
   * clears it, and a new chunk has none.
   */
  private String upsertSql(String column) {
    String clearStale =
        EmbeddingIndexState.TARGET_COLUMN.equals(column)
            ? ", "
                + EmbeddingIndexState.EMBEDDING_COLUMN
                + " = CASE WHEN existing.content IS DISTINCT FROM EXCLUDED.content THEN NULL"
                + " ELSE existing."
                + EmbeddingIndexState.EMBEDDING_COLUMN
                + " END"
            : "";
    return "INSERT INTO "
        + schemaName
        + "."
        + tableName
        + " AS existing (id, content, metadata, "
        + column
        + ") VALUES (?, ?, ?::jsonb, ?) ON CONFLICT (id) DO UPDATE SET content = ?,"
        + " metadata = ?::jsonb, "
        + column
        + " = ?"
        + clearStale;
  }

  /**
   * The stored text of {@code documentId}, its chunks in order and capped at {@code limit}
   * characters - what the model step reads for a document whose file is not being parsed anyway.
   */
  public String documentText(UUID documentId, int limit) {
    List<String> texts =
        jdbcTemplate.query(
            "SELECT content FROM "
                + schemaName
                + "."
                + tableName
                + " WHERE metadata->>'"
                + VectorChunkStore.DOCUMENT_ID_METADATA_KEY
                + "' = ? ORDER BY (metadata->>'chunk_index')::int",
            (rs, row) -> rs.getString("content"),
            documentId.toString());
    StringBuilder text = new StringBuilder();
    for (String chunk : texts) {
      if (text.length() >= limit) {
        break;
      }
      text.append(chunk == null ? "" : chunk).append('\n');
    }
    return text.length() <= limit ? text.toString() : text.substring(0, limit);
  }

  /**
   * Rewrites document-level keys on every chunk of {@code documentId} in place (ADR-0024): removes
   * every key in {@code keysToClear} first, then merges {@code values}. Touches neither content nor
   * embedding nor {@code chunk_full_text}, which is what lets a metadata correction skip
   * re-embedding entirely. Takes the document lock before its first row, like {@link
   * #deleteChunksOfDocument}.
   *
   * @return the number of chunks updated
   */
  @Transactional
  public int updateDocumentMetadata(
      UUID documentId, Map<String, Object> values, Set<String> keysToClear) {
    String sql =
        "UPDATE "
            + schemaName
            + "."
            + tableName
            // metadata is a json column (PgVectorStore's own DDL); the jsonb operators need the
            // cast, and the assignment cast back to json is implicit.
            + " SET metadata = (metadata::jsonb"
            + " - ARRAY(SELECT jsonb_array_elements_text(?::jsonb))) || ?::jsonb"
            + " WHERE metadata->>'"
            + VectorChunkStore.DOCUMENT_ID_METADATA_KEY
            + "' = ? AND "
            + DOCUMENT_LOCK_CONDITION;
    return jdbcTemplate.update(
        sql,
        toJson(List.copyOf(keysToClear)),
        toJson(values),
        documentId.toString(),
        documentId.toString());
  }

  /**
   * Locks the libraries the chunks name against deletion until this transaction ends and refuses
   * the write when one is gone or being erased: once an erasure removed a library, no late writer
   * puts anything of it back. Chunks without a library are not checked.
   *
   * @throws ChunkTargetGoneException naming no content, before anything is written
   */
  private void requireWritableLibraries(List<Document> chunks) {
    String[] libraries =
        chunks.stream()
            .map(chunk -> chunk.getMetadata().get(VectorChunkStore.LIBRARY_ID_METADATA_KEY))
            .filter(Objects::nonNull)
            .map(Object::toString)
            .distinct()
            .toArray(String[]::new);
    if (libraries.length == 0) {
      return;
    }
    List<String> writable =
        jdbcTemplate.queryForList(
            "SELECT id::text FROM knowledge_libraries WHERE id = ANY (?::uuid[])"
                + " AND erasure_requested_at IS NULL FOR KEY SHARE",
            String.class,
            (Object) libraries);
    if (writable.size() < libraries.length) {
      throw new ChunkTargetGoneException();
    }
  }

  /**
   * Deletes, in the caller's transaction, every chunk in both stores that carries {@code libraryId}
   * or one of {@code documentIds}.
   *
   * @return how many vector rows went
   */
  public int deleteAllOf(UUID libraryId, Collection<UUID> documentIds) {
    String[] documents = documentIds.stream().map(UUID::toString).toArray(String[]::new);
    int removed =
        jdbcTemplate.update(
            "DELETE FROM " + schemaName + "." + tableName + chunksOf(),
            libraryId.toString(),
            documents);
    jdbcTemplate.update(
        "DELETE FROM chunk_full_text WHERE library_id = ? OR document_id = ANY (?::uuid[])",
        libraryId,
        documents);
    return removed;
  }

  /** How many chunks in both stores still carry {@code libraryId} or one of {@code documentIds}. */
  public long countAllOf(UUID libraryId, Collection<UUID> documentIds) {
    String[] documents = documentIds.stream().map(UUID::toString).toArray(String[]::new);
    Long vectors =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM " + schemaName + "." + tableName + chunksOf(),
            Long.class,
            libraryId.toString(),
            documents);
    Long fullText =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM chunk_full_text WHERE library_id = ?"
                + " OR document_id = ANY (?::uuid[])",
            Long.class,
            libraryId,
            documents);
    return (vectors == null ? 0 : vectors) + (fullText == null ? 0 : fullText);
  }

  private static String chunksOf() {
    return " WHERE metadata->>'"
        + VectorChunkStore.LIBRARY_ID_METADATA_KEY
        + "' = ? OR metadata->>'"
        + VectorChunkStore.DOCUMENT_ID_METADATA_KEY
        + "' = ANY (?)";
  }

  private record ChunkWithEmbedding(Document chunk, float[] embedding) {}

  private String toJson(Object metadata) {
    try {
      return objectMapper.writeValueAsString(metadata);
    } catch (tools.jackson.core.JacksonException e) {
      throw new IllegalStateException("Failed to serialize chunk metadata to JSON", e);
    }
  }
}
