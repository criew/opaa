package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The two precondition-guarded changeSets at the end of {@code changes/001-baseline.yaml}: the
 * expression indexes on {@code vector_store}'s {@code metadata->>'library_id'} and {@code
 * metadata->>'document_id'}. {@code vector_store} is created by Spring AI at application startup,
 * never by Liquibase, so their {@code tableExists} precondition fails on a genuinely fresh install;
 * {@code onFail: CONTINUE} skips them without recording them as executed, and Liquibase retries
 * them on the next start. That mechanic is live behaviour, not a historical transition, which is
 * why these two changeSets stay separate from the grouped ones and keep a test of their own.
 */
class VectorStoreExpressionIndexTest extends AbstractMigrationTest {

  private static final String BASELINE_PATH = "db/changelog/test-master-through-baseline.yaml";
  private static final List<String> CHANGESET_IDS = List.of("001-baseline-n", "001-baseline-o");
  private static final List<String> INDEX_NAMES =
      List.of("idx_vector_store_library_id", "idx_vector_store_document_id");

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return BASELINE_PATH;
  }

  @BeforeEach
  void setUp() throws SQLException {
    connection = connect();
    connection.setAutoCommit(true);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  /**
   * The fixture applied the whole baseline against a database without {@code vector_store} - the
   * state of a fresh install's very first start.
   */
  @Test
  void skipsWithoutFailingAndWithoutRecordingItselfWhenVectorStoreDoesNotExistYet()
      throws SQLException {
    assertThat(tableExists("vector_store")).isFalse();
    for (String indexName : INDEX_NAMES) {
      assertThat(indexExists(indexName)).as("%s must not exist yet", indexName).isFalse();
    }
    for (String changeSetId : CHANGESET_IDS) {
      assertThat(isRecordedAsExecuted(changeSetId))
          .as("%s must stay unrecorded so Liquibase retries it", changeSetId)
          .isFalse();
    }
  }

  @Test
  void retriesSuccessfullyOnceVectorStoreExists() throws Exception {
    createVectorStoreTable();

    applyChangelog(connection, BASELINE_PATH);

    assertIndexesExistAndAreValid();
    for (String changeSetId : CHANGESET_IDS) {
      assertThat(isRecordedAsExecuted(changeSetId)).isTrue();
    }
  }

  /**
   * An interrupted {@code CREATE INDEX CONCURRENTLY} leaves an index of the same name behind marked
   * invalid; the leading {@code DROP INDEX CONCURRENTLY IF EXISTS} is what lets a retried apply
   * finish it instead of failing on the taken name. {@code pg_indexes} lists invalid indexes too,
   * so only checking {@code indisvalid} catches that regression.
   */
  @Test
  void reapplyingAfterAPreExistingIndexOfTheSameNameStillLeavesValidIndexesBehind()
      throws Exception {
    createVectorStoreTable();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE INDEX idx_vector_store_library_id ON public.vector_store"
              + " ((metadata->>'library_id'))");
      statement.execute(
          "CREATE INDEX idx_vector_store_document_id ON public.vector_store"
              + " ((metadata->>'document_id'))");
    }

    applyChangelog(connection, BASELINE_PATH);

    assertIndexesExistAndAreValid();
  }

  /** Mirrors the columns {@code PgVectorStore} creates; the indexes only read {@code metadata}. */
  private void createVectorStoreTable() throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE public.vector_store (id uuid PRIMARY KEY, content text, metadata jsonb,"
              + " embedding vector(3))");
    }
  }

  private void assertIndexesExistAndAreValid() throws SQLException {
    for (String indexName : INDEX_NAMES) {
      String expression = indexName.substring("idx_vector_store_".length());
      try (Statement statement = connection.createStatement();
          ResultSet rs =
              statement.executeQuery(
                  "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = '"
                      + indexName
                      + "'")) {
        assertThat(rs.next()).as("%s must exist", indexName).isTrue();
        assertThat(rs.getString("indexdef"))
            .contains("(((metadata ->> '" + expression + "'::text))");
      }
      try (Statement statement = connection.createStatement();
          ResultSet rs =
              statement.executeQuery(
                  "SELECT indisvalid FROM pg_index WHERE indexrelid = '"
                      + indexName
                      + "'::regclass")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getBoolean(1)).as("%s must have finished building", indexName).isTrue();
      }
    }
  }

  private boolean tableExists(String tableName) throws SQLException {
    try (PreparedStatement statement =
        statementFor(
            "SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name"
                + " = ?",
            tableName)) {
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next();
      }
    }
  }

  private boolean indexExists(String indexName) throws SQLException {
    try (PreparedStatement statement =
        statementFor(
            "SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?", indexName)) {
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next();
      }
    }
  }

  private boolean isRecordedAsExecuted(String changeSetId) throws SQLException {
    try (PreparedStatement statement =
        statementFor("SELECT 1 FROM databasechangelog WHERE id = ?", changeSetId)) {
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next();
      }
    }
  }

  private PreparedStatement statementFor(String sql, String parameter) throws SQLException {
    PreparedStatement statement = connection.prepareStatement(sql);
    statement.setString(1, parameter);
    return statement;
  }
}
