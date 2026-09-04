package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta test for {@code changes/021-drop-chunk-full-text-skip-table.yaml} (#1270): the table {@code
 * changes/009-chunk-full-text-skip-table.yaml} created for the full-text backfill's poison-chunk
 * bookkeeping is gone once the changeset ran, together with its index - and {@code chunk_full_text}
 * itself, which the write path still fills, is untouched by the drop.
 */
class Migration021DropChunkFullTextSkipTableTest extends AbstractMigrationTest {

  private static final String SKIP_TABLE_CHANGELOG =
      "db/changelog/changes/009-chunk-full-text-skip-table.yaml";
  private static final String FULL_TEXT_TABLE_CHANGELOG =
      "db/changelog/changes/003-chunk-full-text-table.yaml";
  private static final String CHANGELOG_PATH =
      "db/changelog/changes/021-drop-chunk-full-text-skip-table.yaml";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    applyChangelog(connection, FULL_TEXT_TABLE_CHANGELOG);
    applyChangelog(connection, SKIP_TABLE_CHANGELOG);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void dropsTheSkipTableAndItsIndex() throws Exception {
    assertThat(tableExists("chunk_full_text_skip")).isTrue();
    assertThat(indexExists("idx_chunk_full_text_skip_library_id")).isTrue();

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(tableExists("chunk_full_text_skip")).isFalse();
    assertThat(indexExists("idx_chunk_full_text_skip_library_id")).isFalse();
  }

  @Test
  void leavesTheFullTextTableItself() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(tableExists("chunk_full_text")).isTrue();
  }

  private boolean tableExists(String tableName) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT to_regclass('public." + tableName + "') IS NOT NULL AS present")) {
      return rs.next() && rs.getBoolean("present");
    }
  }

  private boolean indexExists(String indexName) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT count(*) AS hits FROM pg_indexes WHERE indexname = '" + indexName + "'")) {
      return rs.next() && rs.getInt("hits") > 0;
    }
  }
}
