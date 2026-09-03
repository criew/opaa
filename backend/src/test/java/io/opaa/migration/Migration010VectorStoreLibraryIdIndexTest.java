package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Delta test for {@code changes/010-vector-store-library-id-index.yaml} (#1119): the expression
 * index backing {@code FullTextBackfillProgressService}'s {@code metadata->>'library_id'} predicate
 * on {@code vector_store}.
 *
 * <p>{@code vector_store} is not Liquibase-owned (Spring AI creates it at application startup, see
 * this changeSet's own comment), so each test creates it itself, mirroring the columns {@code
 * PgVectorStore} actually creates, rather than relying on the fixture chain.
 */
class Migration010VectorStoreLibraryIdIndexTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/010-vector-store-library-id-index.yaml";
  private static final String CHANGESET_ID = "010-vector-store-library-id-index";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @AfterEach
  void tearDown() throws SQLException {
    if (connection != null) {
      connection.close();
    }
  }

  @Test
  void createsTheExpressionIndexWhenVectorStoreAlreadyExists() throws Exception {
    connection = connect();
    createVectorStoreTable(connection);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(indexExists("idx_vector_store_library_id")).isTrue();
    assertThat(changeSetIsRecordedAsExecuted()).isTrue();
  }

  /**
   * On a fresh install, Liquibase always finishes before Spring AI creates vector_store (see this
   * changeSet's own comment) - the precondition must skip without failing the migration, and
   * without marking the changeSet as executed, so it is retried on the next application start.
   */
  @Test
  void skipsWithoutFailingWhenVectorStoreDoesNotExistYet() throws Exception {
    connection = connect();

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(indexExists("idx_vector_store_library_id")).isFalse();
    assertThat(changeSetIsRecordedAsExecuted()).isFalse();
  }

  @Test
  void retriesSuccessfullyOnceVectorStoreExists() throws Exception {
    connection = connect();

    applyChangelog(connection, CHANGELOG_PATH);
    assertThat(changeSetIsRecordedAsExecuted()).isFalse();

    createVectorStoreTable(connection);
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(indexExists("idx_vector_store_library_id")).isTrue();
    assertThat(changeSetIsRecordedAsExecuted()).isTrue();
  }

  private static void createVectorStoreTable(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE IF NOT EXISTS public.vector_store ("
              + "  id uuid PRIMARY KEY,"
              + "  content text,"
              + "  metadata json,"
              + "  embedding vector(3)"
              + ")");
    }
  }

  private boolean indexExists(String indexName) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?")) {
      statement.setString(1, indexName);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next();
      }
    }
  }

  private boolean changeSetIsRecordedAsExecuted() throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT 1 FROM databasechangelog WHERE id = ?")) {
      statement.setString(1, CHANGESET_ID);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next();
      }
    }
  }
}
