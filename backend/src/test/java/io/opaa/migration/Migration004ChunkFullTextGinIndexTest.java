package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Delta test for {@code changes/004-chunk-full-text-gin-index.yaml} (issue #1047, AP2a): applies
 * the baseline, then 003 ({@code chunk_full_text} itself) and 004 (this changeSet) in sequence, and
 * asserts the GIN index on {@code content_tsv} - built {@code CONCURRENTLY}, outside 004's own
 * transaction, so it does not block concurrent writes to an already-populated table for the
 * duration of the build (see that changeSet's own comment).
 */
class Migration004ChunkFullTextGinIndexTest extends AbstractMigrationTest {

  private static final String TABLE_CHANGELOG_PATH =
      "db/changelog/changes/003-chunk-full-text-table.yaml";
  private static final String GIN_INDEX_CHANGELOG_PATH =
      "db/changelog/changes/004-chunk-full-text-gin-index.yaml";
  private static final String GIN_INDEX_NAME = "idx_chunk_full_text_content_tsv";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    applyChangelog(connection, TABLE_CHANGELOG_PATH);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void theGinIndexExistsIsValidAndSupportsFullTextMatching() throws Exception {
    applyChangelog(connection, GIN_INDEX_CHANGELOG_PATH);

    assertGinIndexExistsAndIsValid();

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

  /**
   * #1047 review, finding 6: proves the leading {@code DROP INDEX CONCURRENTLY IF EXISTS} actually
   * lets a retried apply of this changeSet finish the index even when an index of the same name
   * already exists - the situation a previous, interrupted {@code CREATE INDEX CONCURRENTLY} leaves
   * behind (PostgreSQL marks that index invalid rather than removing it). A hand-created, valid
   * index under the same name stands in for that leftover here (reproducing a genuinely *invalid*
   * index deterministically would require killing the server mid-build) - the property under test,
   * "the changeSet's own CREATE INDEX CONCURRENTLY does not silently no-op because the name is
   * already taken", is exercised identically either way, since {@code IF EXISTS} does not
   * distinguish valid from invalid.
   */
  @Test
  void reapplyingAfterAPreExistingIndexOfTheSameNameStillLeavesAValidIndexBehind()
      throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE INDEX " + GIN_INDEX_NAME + " ON public.chunk_full_text USING gin (content_tsv)");
    }

    applyChangelog(connection, GIN_INDEX_CHANGELOG_PATH);

    assertGinIndexExistsAndIsValid();
  }

  private void assertGinIndexExistsAndIsValid() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' "
                    + "AND indexname = '"
                    + GIN_INDEX_NAME
                    + "'")) {
      assertThat(rs.next()).as("GIN index must exist").isTrue();
      assertThat(rs.getString("indexdef")).containsIgnoringCase("USING gin");
    }
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT indisvalid FROM pg_index WHERE indexrelid = '"
                    + GIN_INDEX_NAME
                    + "'::regclass")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getBoolean("indisvalid")).as("index must have finished building").isTrue();
    }
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
}
