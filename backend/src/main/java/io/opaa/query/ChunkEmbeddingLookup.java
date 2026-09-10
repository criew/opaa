package io.opaa.query;

import com.pgvector.PGvector;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

/**
 * Reads chunk embeddings straight out of the pgvector table by row id - one {@code SELECT ... WHERE
 * id::text = ANY(?)} over the MMR candidate ids, never an embedding-API call: the vector already
 * sits in the row {@code similaritySearch} read, but its {@code Document} result does not carry it.
 * The {@code id::text} cast keeps the query agnostic to {@code
 * spring.ai.vectorstore.pgvector.id-type}.
 *
 * <p>Schema and table name come from the same {@code spring.ai.vectorstore.pgvector.*} properties
 * {@link org.springframework.ai.vectorstore.pgvector.PgVectorStore} binds, with the same defaults -
 * never hardcoded independently of that configuration.
 */
@Component
class ChunkEmbeddingLookup {

  private final JdbcTemplate jdbcTemplate;
  private final String schemaName;
  private final String tableName;

  ChunkEmbeddingLookup(
      JdbcTemplate jdbcTemplate,
      @Value("${spring.ai.vectorstore.pgvector.schema-name:public}") String schemaName,
      @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {
    this.jdbcTemplate = jdbcTemplate;
    this.schemaName = schemaName;
    this.tableName = tableName;
  }

  /**
   * Looks up the embedding of every given chunk id, keyed by id. A row deleted between the search
   * and this lookup simply does not appear in the result; {@link MmrSelector} treats a missing
   * entry as zero similarity.
   */
  Map<String, float[]> findByIds(List<String> chunkIds) {
    if (chunkIds.isEmpty()) {
      return Map.of();
    }
    String sql =
        "SELECT id, embedding FROM " + schemaName + "." + tableName + " WHERE id::text = ANY(?)";
    Map<String, float[]> embeddingsById = new HashMap<>();
    // RowCallbackHandler, not ResultSetExtractor: JdbcTemplate itself advances the cursor and
    // invokes this once per row - a caller-side rs.next() loop here would silently skip rows.
    jdbcTemplate.query(
        sql,
        ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", chunkIds.toArray())),
        (RowCallbackHandler)
            rs -> embeddingsById.put(rs.getString("id"), parseVector(rs.getString("embedding"))));
    return embeddingsById;
  }

  private static float[] parseVector(String pgvectorTextValue) {
    try {
      return new PGvector(pgvectorTextValue).toArray();
    } catch (SQLException e) {
      throw new IllegalStateException(
          "Malformed pgvector value read back from the vector store: " + pgvectorTextValue, e);
    }
  }
}
