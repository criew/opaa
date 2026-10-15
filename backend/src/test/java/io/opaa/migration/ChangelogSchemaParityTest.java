package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.opaa.compliance.ChangelogSchema;
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
 * {@link ChangelogSchema} reads the changelog without a database, so the compliance guard runs in
 * every unit test run. This test holds its reading against the catalog of a database the master
 * changelog migrated - the same tables, partitions counted as their parent, and the same columns -
 * and its fixed columns of {@code vector_store} against the table Spring AI creates.
 */
class ChangelogSchemaParityTest extends AbstractMigrationTest {

  @Override
  protected List<String> baseFixtureChangelogs() {
    return List.of(MasterChangelog.PATH);
  }

  @Test
  void theChangelogReadWithoutADatabaseHasTheTablesAndColumnsOfAMigratedOne() throws SQLException {
    Map<String, Set<String>> read = new TreeMap<>();
    ChangelogSchema.read()
        .forEach((table, columns) -> read.put(table, new TreeSet<>(columns.columns().keySet())));

    try (Connection connection = connect()) {
      assertThat(read).isEqualTo(catalog(connection));
    }
  }

  /** Built as application.yml configures {@code spring.ai.vectorstore.pgvector}. */
  @Test
  void springAiCreatesVectorStoreWithTheColumnsTheGuardAssumes() throws SQLException {
    try (Connection connection = connect()) {
      PgVectorStore.builder(
              new JdbcTemplate(new SingleConnectionDataSource(connection, true)),
              mock(EmbeddingModel.class))
          .schemaName("public")
          .dimensions(1024)
          .indexType(PgIndexType.HNSW)
          .distanceType(PgDistanceType.COSINE_DISTANCE)
          .initializeSchema(true)
          .build()
          .afterPropertiesSet();

      Map<String, Set<String>> expected = new TreeMap<>();
      ChangelogSchema.TABLES_OUTSIDE_THE_CHANGELOG.forEach(
          (table, columns) -> expected.put(table, new TreeSet<>(columns)));
      Map<String, Set<String>> created = catalog(connection);
      created.keySet().retainAll(expected.keySet());

      assertThat(created).isEqualTo(expected);
    }
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
                    + " AND c.relname NOT IN ('databasechangelog', 'databasechangeloglock')")) {
      while (rows.next()) {
        catalog.computeIfAbsent(rows.getString(1), table -> new TreeSet<>()).add(rows.getString(2));
      }
    }
    return catalog;
  }
}
