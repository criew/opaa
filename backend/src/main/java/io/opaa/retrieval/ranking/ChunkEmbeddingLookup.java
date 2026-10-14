package io.opaa.retrieval.ranking;

import com.pgvector.PGvector;
import io.opaa.knowledge.VectorTable;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

/**
 * Reads chunk embeddings straight out of the pgvector table by row id - one {@code SELECT ... WHERE
 * id::text = ANY(?)} over the MMR candidate ids, never an embedding-API call: the vector already
 * sits in the row {@code similaritySearch} read, but its {@code Document} result does not carry it.
 * The {@code id::text} cast keeps the query agnostic to {@code
 * spring.ai.vectorstore.pgvector.id-type}.
 */
@Component
public class ChunkEmbeddingLookup {

  private final JdbcTemplate jdbcTemplate;
  private final VectorTable vectorTable;

  ChunkEmbeddingLookup(JdbcTemplate jdbcTemplate, VectorTable vectorTable) {
    this.jdbcTemplate = jdbcTemplate;
    this.vectorTable = vectorTable;
  }

  /**
   * Looks up the embedding of every given chunk id, keyed by id. A row deleted between the search
   * and this lookup, or one without a vector (written while the index was stale), does not appear
   * in the result; {@link MmrSelector} treats a missing entry as zero similarity.
   */
  public Map<String, float[]> findByIds(List<String> chunkIds) {
    if (chunkIds.isEmpty()) {
      return Map.of();
    }
    String sql =
        "SELECT id, embedding FROM "
            + vectorTable.qualifiedName()
            + " WHERE id::text = ANY(?) AND embedding IS NOT NULL";
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
