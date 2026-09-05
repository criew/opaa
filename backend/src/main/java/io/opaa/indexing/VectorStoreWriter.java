package io.opaa.indexing;

import com.pgvector.PGvector;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * <p>Schema and table name come from the same {@code spring.ai.vectorstore.pgvector.*} properties
 * {@code PgVectorStore} binds; {@code id-type} is assumed {@code UUID}, as elsewhere.
 */
@Component
public class VectorStoreWriter {

  private final JdbcTemplate jdbcTemplate;
  private final FullTextChunkStore fullTextChunkStore;
  private final ObjectMapper objectMapper;
  private final String schemaName;
  private final String tableName;

  public VectorStoreWriter(
      JdbcTemplate jdbcTemplate,
      FullTextChunkStore fullTextChunkStore,
      ObjectMapper objectMapper,
      @Value("${spring.ai.vectorstore.pgvector.schema-name:public}") String schemaName,
      @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {
    this.jdbcTemplate = jdbcTemplate;
    this.fullTextChunkStore = fullTextChunkStore;
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
    writeEmbeddedChunks(chunks, embeddings, null);
  }

  /**
   * {@link #writeEmbeddedChunks(List, List)} with a document-level supplement for the full-text
   * index only - the document's freie Schlagworte, which never become chunk text or chunk metadata.
   */
  @Transactional
  public void writeEmbeddedChunks(
      List<Document> chunks, List<float[]> embeddings, String fullTextSupplement) {
    if (chunks.isEmpty()) {
      return;
    }
    // Zipped up front, not looked up per row via chunks.indexOf(chunk) inside the callback below:
    // JdbcTemplate's ParameterizedPreparedStatementSetter only ever hands back the row item itself,
    // never its index, and indexOf would additionally be wrong for two structurally equal Document
    // instances (only the first match would ever be found).
    List<ChunkWithEmbedding> zipped = new ArrayList<>(chunks.size());
    for (int i = 0; i < chunks.size(); i++) {
      zipped.add(new ChunkWithEmbedding(chunks.get(i), embeddings.get(i)));
    }

    String sql =
        "INSERT INTO "
            + schemaName
            + "."
            + tableName
            + " (id, content, metadata, embedding) VALUES (?, ?, ?::jsonb, ?) "
            + "ON CONFLICT (id) DO UPDATE SET content = ?, metadata = ?::jsonb, embedding = ?";
    jdbcTemplate.batchUpdate(
        sql,
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
        });
    fullTextChunkStore.indexChunks(chunks, fullTextSupplement);
  }

  /**
   * Rebuilds the {@code chunk_full_text} rows of {@code documentId} from the chunk text already in
   * the vector store, with {@code supplement} appended - the path keywords assigned after the
   * ingest (the Bestandslauf) take into the lexical index without re-embedding anything.
   *
   * @return the number of chunks re-indexed
   */
  @Transactional
  public int reindexFullText(UUID documentId, String supplement) {
    List<Document> chunks =
        jdbcTemplate.query(
            "SELECT id, content, metadata FROM "
                + schemaName
                + "."
                + tableName
                + " WHERE metadata->>'"
                + VectorChunkStore.DOCUMENT_ID_METADATA_KEY
                + "' = ?",
            (rs, row) ->
                Document.builder()
                    .id(rs.getString("id"))
                    .text(rs.getString("content"))
                    .metadata(readMetadata(rs.getString("metadata")))
                    .build(),
            documentId.toString());
    fullTextChunkStore.indexChunks(chunks, supplement);
    return chunks.size();
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

  @SuppressWarnings("unchecked")
  private Map<String, Object> readMetadata(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    return objectMapper.readValue(json, Map.class);
  }

  /**
   * Rewrites document-level keys on every chunk of {@code documentId} in place (ADR-0024): removes
   * every key in {@code keysToClear} first, then merges {@code values}. Touches neither content nor
   * embedding nor {@code chunk_full_text}, which is what lets a metadata correction skip
   * re-embedding entirely.
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
            + "' = ?";
    return jdbcTemplate.update(
        sql, toJson(List.copyOf(keysToClear)), toJson(values), documentId.toString());
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
