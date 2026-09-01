package io.opaa.indexing;

import com.pgvector.PGvector;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * transaction (#1047 review, finding 3) - deliberately a plain JDBC upsert mirroring {@code
 * PgVectorStore#doAdd}'s own SQL, not a call to {@link
 * org.springframework.ai.vectorstore.VectorStore#add}: that call embeds <em>and</em> writes in one
 * step, which would hold the connection this transaction needs for the whole embedding HTTP round
 * trip. {@link VectorChunkStore#addChunks} embeds first, outside any transaction, and only calls
 * into this class afterwards - so the connection this class checks out is held for a handful of
 * local {@code INSERT}s, never for a network call. Without that split, {@code embeddingConcurrency}
 * sub-batches (up to 32, see {@code IndexingProperties}) each holding a connection for the duration
 * of an embedding call can exceed HikariCP's default {@code maximum-pool-size} of 10, starving the
 * query path of connections.
 *
 * <p>Schema/table name are read from the same {@code spring.ai.vectorstore.pgvector.*} properties
 * {@code PgVectorStore} itself binds, with the same defaults ({@code public}/{@code vector_store})
 * - mirrors {@code io.opaa.query.ChunkEmbeddingLookup}'s own pattern for the same reason (never
 * hardcoded independently of that configuration). {@code id-type} is assumed {@code UUID}, exactly
 * as {@code ChunkEmbeddingLookup} already assumes - this project never overrides it.
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
  void writeEmbeddedChunks(List<Document> chunks, List<float[]> embeddings) {
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
    fullTextChunkStore.indexChunks(chunks);
  }

  private record ChunkWithEmbedding(Document chunk, float[] embedding) {}

  private String toJson(Map<String, Object> metadata) {
    try {
      return objectMapper.writeValueAsString(metadata);
    } catch (tools.jackson.core.JacksonException e) {
      throw new IllegalStateException("Failed to serialize chunk metadata to JSON", e);
    }
  }
}
