package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta test for {@code changes/009-chunk-full-text-skip-table.yaml} (#1093): the table {@code
 * io.opaa.indexing.FullTextBackfillService} uses to permanently record a chunk it gave up indexing
 * into {@code chunk_full_text} - the schema {@code FullTextChunkStore#recordSkip} and {@code
 * FullTextBackfillProgressService} both depend on.
 */
class Migration009ChunkFullTextSkipTableTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/009-chunk-full-text-skip-table.yaml";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    applyChangelog(connection, CHANGELOG_PATH);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void createsTheChunkFullTextSkipTableWithItsColumns() throws SQLException {
    assertThat(tableExists("chunk_full_text_skip")).isTrue();
    assertThat(columnType("chunk_id")).isEqualTo("uuid");
    assertThat(columnType("document_id")).isEqualTo("uuid");
    assertThat(columnType("library_id")).isEqualTo("uuid");
    assertThat(columnType("content_tsv_version")).isEqualTo("smallint");
    assertThat(columnType("error_message")).isEqualTo("text");
    assertThat(columnType("skipped_at")).isEqualTo("timestamp with time zone");
  }

  @Test
  void chunkIdIsThePrimaryKeyAndRejectsDuplicates() throws SQLException {
    UUID chunkId = UUID.randomUUID();
    insertRow(chunkId, UUID.randomUUID(), UUID.randomUUID(), (short) 1, "first failure");

    assertThatThrownBy(
            () ->
                insertRow(
                    chunkId, UUID.randomUUID(), UUID.randomUUID(), (short) 1, "second failure"))
        .isInstanceOf(SQLException.class);
  }

  @Test
  void libraryIdColumnIsIndexed() throws SQLException {
    assertThat(indexExists("idx_chunk_full_text_skip_library_id")).isTrue();
  }

  private void insertRow(
      UUID chunkId, UUID documentId, UUID libraryId, short version, String errorMessage)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO chunk_full_text_skip (chunk_id, document_id, library_id, "
                + "content_tsv_version, error_message) VALUES (?, ?, ?, ?, ?)")) {
      statement.setObject(1, chunkId);
      statement.setObject(2, documentId);
      statement.setObject(3, libraryId);
      statement.setShort(4, version);
      statement.setString(5, errorMessage);
      statement.executeUpdate();
    }
  }

  private boolean tableExists(String tableName) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name = ?")) {
      statement.setString(1, tableName);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next();
      }
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

  private String columnType(String columnName) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT data_type FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = 'chunk_full_text_skip' AND"
                + " column_name = ?")) {
      statement.setString(1, columnName);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).as("column %s must exist", columnName).isTrue();
        return rs.getString("data_type");
      }
    }
  }
}
