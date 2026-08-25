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
 * Reads chunk embeddings straight out of the pgvector table by row id (#914) - a single {@code
 * SELECT ... WHERE id::text = ANY(?)} over the {@code fetchK} MMR candidate ids per query, not an
 * embedding-API call: {@code similaritySearch}'s own {@link
 * org.springframework.ai.document.Document} result never carries the stored vector (see {@link
 * MmrSelector}'s Javadoc for why), but the vector is sitting right there in the same table row
 * {@code similaritySearch} already read to compute the distance it did return. {@code id::text} on
 * the left side (rather than binding a typed {@code uuid} array) keeps this query agnostic to
 * {@code spring.ai.vectorstore.pgvector.id-type} - this project never overrides it away from the
 * default ({@code PgVectorStore.PgIdType.UUID}), but the cast costs nothing and avoids a second
 * place that would silently break if that ever changed.
 *
 * <p>Schema/table name are read from the same {@code spring.ai.vectorstore.pgvector.*} properties
 * {@link org.springframework.ai.vectorstore.pgvector.PgVectorStore} itself binds, with the same
 * defaults ({@code public}/{@code vector_store}) - never hardcoded independently of that
 * configuration.
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
   * Looks up the embedding of every given chunk id, keyed by id. A row deleted between {@code
   * similaritySearch} and this lookup (re-indexing race) simply does not appear in the result - see
   * {@link MmrSelector}'s handling of a missing entry.
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
