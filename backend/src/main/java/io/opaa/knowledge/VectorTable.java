package io.opaa.knowledge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The table that holds the chunks and their embeddings, as {@code schema.tableName}. The schema is
 * the application schema ({@code opaa.database.schema}, a plain identifier the start-up guard has
 * checked) and the table name the one {@code PgVectorStore} binds, with its default; every native
 * statement names the table through {@link #qualifiedName()}.
 */
@Component
public record VectorTable(String schema, String tableName) {

  public VectorTable(
      @Value("${opaa.database.schema}") String schema,
      @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {
    this.schema = schema;
    this.tableName = tableName;
  }

  public String qualifiedName() {
    return schema + "." + tableName;
  }
}
