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
 * Delta test for {@code changes/003-chunk-full-text-table.yaml} (issue #1047, AP2a): applies the
 * baseline, then this one changeSet in isolation, and asserts the resulting {@code chunk_full_text}
 * table and its btree indexes - the schema the AP2a write path (VectorChunkStore /
 * FullTextChunkStore) depends on. The GIN index on {@code content_tsv} is a separate changeset
 * (004, {@link Migration004ChunkFullTextGinIndexTest}) - {@code CREATE INDEX CONCURRENTLY} cannot
 * share a transaction with this one's table creation.
 *
 * <p>Deliberately a table of its own rather than columns added to {@code vector_store}: {@code
 * vector_store} is created by Spring AI at application startup, never by Liquibase (see the
 * changeSet's own comment) - it does not exist in this test's fixture chain at all, which is
 * exactly why altering it directly here is not an option this delta test could ever exercise.
 */
class Migration003ChunkFullTextTableTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/003-chunk-full-text-table.yaml";

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
    assertThat(columnType("content_tsv_version")).isEqualTo("smallint");
  }

  @Test
  void contentTsvVersionDefaultsToOne() throws SQLException {
    UUID chunkId = insertRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "text");

    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT content_tsv_version FROM chunk_full_text WHERE chunk_id = ?")) {
      statement.setObject(1, chunkId);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getShort("content_tsv_version")).isEqualTo((short) 1);
      }
    }
  }

  @Test
  void chunkIdIsThePrimaryKeyAndRejectsDuplicates() throws SQLException {
    UUID chunkId = UUID.randomUUID();
    insertRow(chunkId, UUID.randomUUID(), UUID.randomUUID(), "erste Fassung");

    assertThatThrownBy(() -> insertRow(chunkId, UUID.randomUUID(), UUID.randomUUID(), "duplikat"))
        .isInstanceOf(SQLException.class);
  }

  @Test
  void documentIdAndLibraryIdColumnsAreIndexed() throws SQLException {
    assertThat(indexExists("idx_chunk_full_text_document_id")).isTrue();
    assertThat(indexExists("idx_chunk_full_text_library_id")).isTrue();
  }

  @Test
  void theGinIndexDoesNotExistYet() throws SQLException {
    // Confirms the split between 003 (table) and 004 (GIN index, CONCURRENTLY) is real: this
    // changeSet alone must not create it.
    assertThat(indexExists("idx_chunk_full_text_content_tsv")).isFalse();
  }

  private UUID insertRow(UUID chunkId, UUID documentId, UUID libraryId, String content)
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
    return chunkId;
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
