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
 * Delta test for {@code changes/019-documents-library-status-index.yaml} (#1067): the index on
 * {@code documents (library_id, status)} backing the backfill selection and the per-library fill
 * state of {@code MetadataBackfillService}, both of which filter one library's {@code INDEXED} rows
 * on every status load.
 */
class Migration019DocumentsLibraryStatusIndexTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/019-documents-library-status-index.yaml";
  private static final String INDEX_NAME = "idx_documents_library_status";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws SQLException {
    connection = connect();
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void createsTheIndexOnLibraryIdAndStatus() throws Exception {
    assertThat(indexDef()).isNull();

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(indexDef()).isNotNull().contains("(library_id, status)");
  }

  private String indexDef() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT indexdef FROM pg_indexes WHERE indexname = '" + INDEX_NAME + "'")) {
      return rs.next() ? rs.getString("indexdef") : null;
    }
  }
}
