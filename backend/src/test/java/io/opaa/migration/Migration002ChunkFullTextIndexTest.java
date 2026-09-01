package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta test for {@code changes/002-chunk-full-text-index.yaml} (issue #1047, AP2a): applies the
 * baseline, then this one changeSet in isolation, and asserts the resulting {@code chunk_full_text}
 * table and its indexes - the schema the AP2a write path (VectorChunkStore / FullTextChunkStore)
 * and backfill (FullTextBackfillService) both depend on.
 *
 * <p>Deliberately a table of its own rather than columns added to {@code vector_store}: {@code
 * vector_store} is created by Spring AI at application startup, never by Liquibase (see the
 * changeSet's own comment) - it does not exist in this test's fixture chain at all, which is
 * exactly why altering it directly here is not an option this delta test could ever exercise.
 */
class Migration002ChunkFullTextIndexTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/002-chunk-full-text-index.yaml";

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
  void createsTheChunkFullTextTableWithItsColumns() throws SQLException {
    assertThat(tableExists("chunk_full_text")).isTrue();
    assertThat(columnType("chunk_id")).isEqualTo("uuid");
    assertThat(columnType("document_id")).isEqualTo("uuid");
    assertThat(columnType("library_id")).isEqualTo("uuid");
    assertThat(columnType("content_tsv")).isEqualTo("tsvector");
  }

  @Test
  void chunkIdIsThePrimaryKeyAndRejectsDuplicates() throws SQLException {
    UUID chunkId = UUID.randomUUID();
    insertRow(chunkId, UUID.randomUUID(), UUID.randomUUID(), "erste Fassung");

    assertThatThrownBy(() -> insertRow(chunkId, UUID.randomUUID(), UUID.randomUUID(), "duplikat"))
        .isInstanceOf(SQLException.class);
  }

  @Test
  void theGinIndexExistsIsValidAndSupportsFullTextMatching() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' "
                    + "AND indexname = 'idx_chunk_full_text_content_tsv'")) {
      assertThat(rs.next()).as("GIN index must exist").isTrue();
      assertThat(rs.getString("indexdef")).containsIgnoringCase("USING gin");
    }
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT indisvalid FROM pg_index "
                    + "WHERE indexrelid = 'idx_chunk_full_text_content_tsv'::regclass")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getBoolean("indisvalid")).as("index must have finished building").isTrue();
    }

    UUID chunkId = UUID.randomUUID();
    insertRow(
        chunkId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "Befreiung von der Verwaltungsgebühr wegen Bedürftigkeit");

    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM chunk_full_text WHERE chunk_id = ? "
                + "AND content_tsv @@ to_tsquery('german', 'Bedürftigkeit')")) {
      statement.setObject(1, chunkId);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).as("the GIN index must support a real tsquery match").isTrue();
      }
    }
  }

  @Test
  void documentIdAndLibraryIdColumnsAreIndexed() throws SQLException {
    assertThat(indexExists("idx_chunk_full_text_document_id")).isTrue();
    assertThat(indexExists("idx_chunk_full_text_library_id")).isTrue();
  }

  private void insertRow(UUID chunkId, UUID documentId, UUID libraryId, String content)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO chunk_full_text (chunk_id, document_id, library_id, content_tsv) "
                + "VALUES (?, ?, ?, to_tsvector('german', ?))")) {
      statement.setObject(1, chunkId);
      statement.setObject(2, documentId);
      statement.setObject(3, libraryId);
      statement.setString(4, content);
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
                + "WHERE table_schema = 'public' AND table_name = 'chunk_full_text' AND column_name"
                + " = ?")) {
      statement.setString(1, columnName);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).as("column %s must exist", columnName).isTrue();
        return rs.getString("data_type");
      }
    }
  }
}
