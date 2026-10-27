package io.opaa.knowledge;

import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The table that holds the chunks and their embeddings, as {@code schema.tableName}: the schema and
 * table name {@code PgVectorStore} binds. The start-up guard holds the schema equal to the
 * application schema ({@code opaa.database.schema}, a plain identifier); every native statement
 * names the table through {@link #qualifiedName()}.
 */
@Component
public record VectorTable(String schema, String tableName) {

  @Autowired
  public VectorTable(PgVectorStoreProperties properties) {
    this(properties.getSchemaName(), properties.getTableName());
  }

  public String qualifiedName() {
    return schema + "." + tableName;
  }

  /**
   * Whether the table exists. It does not before the first embedding model is installed (ADR-0060,
   * Entscheidung 10): a reader then finds no chunk and a deleter nothing to delete, so callers ask
   * this before a statement that names the table, never by catching its failure.
   */
  public boolean existsIn(JdbcTemplate jdbcTemplate) {
    return Boolean.TRUE.equals(
        jdbcTemplate.queryForObject(
            "SELECT to_regclass(?) IS NOT NULL", Boolean.class, qualifiedName()));
  }
}
