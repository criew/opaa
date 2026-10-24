package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.opaa.compliance.MigrationSchema;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * {@link MigrationSchema} reads the migrations without a database, so the compliance guard runs in
 * every unit test run. This test holds its reading against the catalog of a database the migrations
 * built - the same tables, partitions counted as their parent, and the same columns - and the
 * migrations' {@code vector_store} against the table Spring AI creates.
 */
class MigrationSchemaParityTest extends AbstractMigrationTest {

  @Override
  protected List<String> baseFixtureMigrations() {
    return Migrations.all();
  }

  @Test
  void theMigrationsReadWithoutADatabaseHaveTheTablesAndColumnsOfAMigratedOne()
      throws SQLException {
    Map<String, Set<String>> read = new TreeMap<>();
    MigrationSchema.read()
        .forEach((table, columns) -> read.put(table, new TreeSet<>(columns.columns().keySet())));

    try (Connection connection = connect()) {
      assertThat(read).isEqualTo(catalog(connection));
    }
  }

  /**
   * The knowledge migration creates {@code vector_store} with the columns and types Spring AI's
   * {@code PgVectorStore} creates and reads, built as application.yml configures {@code
   * spring.ai.vectorstore.pgvector}: a table Spring AI created on an older installation and one the
   * migration creates are the same to the application.
   */
  @Test
  void theMigrationCreatesVectorStoreAsSpringAiDoes() throws SQLException {
    Map<String, String> springAi;
    try (Connection sibling = connectToSiblingDatabase();
        Statement statement = sibling.createStatement()) {
      statement.execute("DROP TABLE vector_store");
      PgVectorStore.builder(
              new JdbcTemplate(new SingleConnectionDataSource(sibling, true)),
              mock(EmbeddingModel.class))
          .schemaName("public")
          .dimensions(1024)
          .indexType(PgIndexType.HNSW)
          .distanceType(PgDistanceType.COSINE_DISTANCE)
          .initializeSchema(true)
          .build()
          .afterPropertiesSet();
      springAi = vectorStoreColumns(sibling);
    }

    try (Connection connection = connect()) {
      assertThat(vectorStoreColumns(connection))
          .containsOnlyKeys("id", "content", "metadata", "embedding")
          .isEqualTo(springAi);
    }
  }

  private static Map<String, String> vectorStoreColumns(Connection connection) throws SQLException {
    Map<String, String> columns = new TreeMap<>();
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT attname, format_type(atttypid, atttypmod) FROM pg_attribute"
                    + " WHERE attrelid = 'vector_store'::regclass AND attnum > 0"
                    + " AND NOT attisdropped")) {
      while (rows.next()) {
        columns.put(rows.getString(1), rows.getString(2));
      }
    }
    return columns;
  }

  private static Map<String, Set<String>> catalog(Connection connection) throws SQLException {
    Map<String, Set<String>> catalog = new TreeMap<>();
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT c.relname, a.attname FROM pg_class c"
                    + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                    + " JOIN pg_attribute a ON a.attrelid = c.oid"
                    + " WHERE n.nspname = current_schema() AND c.relkind IN ('r', 'p')"
                    + " AND NOT c.relispartition AND a.attnum > 0 AND NOT a.attisdropped"
                    + " AND c.relname <> 'flyway_schema_history'")) {
      while (rows.next()) {
        catalog.computeIfAbsent(rows.getString(1), table -> new TreeSet<>()).add(rows.getString(2));
      }
    }
    return catalog;
  }
}
