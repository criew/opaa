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
 * Delta test for {@code db/changelog/changes/002-documents-indexed-chunk-count-index.yaml} (#1090):
 * the partial index backing {@code LowChunkDocumentAuditService#findLowChunkDocuments}.
 */
class Migration002DocumentsIndexedChunkCountIndexTest extends AbstractMigrationTest {

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
  void createsAPartialIndexOnOrganizationIdAndChunkCountScopedToIndexedDocuments()
      throws Exception {
    assertThat(indexDef()).isNull();

    applyChangelog(connection, "db/changelog/changes/002-documents-indexed-chunk-count-index.yaml");

    String indexDef = indexDef();
    assertThat(indexDef)
        .isNotNull()
        .contains("(organization_id, chunk_count)")
        .contains("WHERE ((status)::text = 'INDEXED'::text)");
  }

  /**
   * {@code indexdef} of {@code idx_documents_indexed_chunk_count}, or null if it does not exist.
   */
  private String indexDef() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT indexdef FROM pg_indexes WHERE indexname ="
                    + " 'idx_documents_indexed_chunk_count'")) {
      return rs.next() ? rs.getString("indexdef") : null;
    }
  }
}
