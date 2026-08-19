package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Applies Liquibase changelog 026 in isolation against a database built from the real, versioned
 * changelog through changeSet 020 - the same {@code test-master-through-020.yaml} base {@code
 * Migration024AllowRssFeedSourceTypeTest} already uses, since {@code source_entry_url} (like 024's
 * widened constraint) only ever touches {@code documents} and is unaffected by the audit chain
 * (021-023) or {@code rss_feed_state} (025).
 *
 * <p>Covers #468's acceptance criterion that an attachment document's origin entry is recorded -
 * checked against a Liquibase-built schema, not a Hibernate-built one, so a pre-existing row (added
 * before this migration ran) and the column's nullability are both proven against the real DDL, not
 * assumed from the entity mapping (see AGENTS.md's reproduction-proof guidance on schema parity
 * between test and production).
 */
@Testcontainers(disabledWithoutDocker = true)
class Migration026AddSourceEntryUrlTest extends AbstractMigrationTest {

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-020.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void columnIsNullableAndDefaultsToNullOnAPreExistingRow() throws Exception {
    // A row written before 026 ran - the migration must not fail re-validating it, and the new
    // column must default to NULL rather than requiring a backfill.
    UUID legacyDoc = insertDocument("legacy.html", null);

    applyChangelog026();

    assertThat(sourceEntryUrl(legacyDoc)).isNull();
  }

  @Test
  void anAttachmentsSourceEntryUrlIsStoredAndReadBack() throws Exception {
    applyChangelog026();

    UUID attachment = insertDocument("anlage.pdf", "https://example.gov/artikel/mein-artikel");

    assertThat(sourceEntryUrl(attachment)).isEqualTo("https://example.gov/artikel/mein-artikel");
  }

  @Test
  void aDocumentWithoutASourceEntryUrlStaysNull() throws Exception {
    // The RSS entry's own document row (not an attachment) never has a source_entry_url - proves
    // the column stays optional after the migration, not just before it.
    applyChangelog026();

    UUID entryDocument = insertDocument("feed-entry.html", null);

    assertThat(sourceEntryUrl(entryDocument)).isNull();
  }

  private void applyChangelog026() throws Exception {
    applyChangelog(connection, "db/changelog/changes/026-add-source-entry-url-to-documents.yaml");
  }

  /**
   * Inserts a row with {@code source_type = 'FILESYSTEM'} - always accepted by {@code
   * chk_documents_source_type} at every point this class applies it (through-020's constraint,
   * unaffected by 026), regardless of whether 026 has run yet. {@code source_entry_url} is never
   * this test's concern for identifying an RSS attachment specifically, only for the column's own
   * behaviour (nullable, round-trips a value).
   */
  private UUID insertDocument(String fileName, String sourceEntryUrl) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      String sourceEntryUrlColumn = sourceEntryUrl == null ? "" : ", source_entry_url";
      String sourceEntryUrlValue = sourceEntryUrl == null ? "" : ", '" + sourceEntryUrl + "'";
      statement.execute(
          "INSERT INTO documents (id, file_name, file_path, status, source_type, library_id,"
              + " organization_id"
              + sourceEntryUrlColumn
              + ") VALUES ('"
              + id
              + "', '"
              + fileName
              + "', '/corpus/"
              + fileName
              + "', 'INDEXED', 'FILESYSTEM', '00000000-0000-0000-0000-000000000002', '"
              + SEEDED_ORGANIZATION_ID
              + "'"
              + sourceEntryUrlValue
              + ")");
    }
    return id;
  }

  private String sourceEntryUrl(UUID documentId) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT source_entry_url FROM documents WHERE id = '" + documentId + "'")) {
      return result.next() ? result.getString(1) : null;
    }
  }
}
