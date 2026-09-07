package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
 * Delta tests for {@code changes/032-s3-events.yaml} (ADR-0027, #1381): the push-secret column
 * renamed from its Confluence-only name with its value preserved, and the run-mode check learning
 * {@code EVENT}. Against the state migration 013 (run mode) and 014 (webhook secret) leave behind.
 */
class Migration032S3EventsTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH = "db/changelog/changes/032-s3-events.yaml";
  private static final String ORGANIZATION = "00000000-0000-0000-0000-000000000001";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    applyChangelog(connection, "db/changelog/changes/010-confluence-source-type.yaml");
    applyChangelog(connection, "db/changelog/changes/013-confluence-full-sync.yaml");
    applyChangelog(connection, "db/changelog/changes/014-confluence-webhooks.yaml");
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void beforeTheMigrationTheColumnCarriesItsOldNameAndEventIsRejected() throws Exception {
    assertThat(columnExists("knowledge_libraries", "source_confluence_webhook_secret")).isTrue();
    assertThat(columnExists("knowledge_libraries", "source_webhook_secret")).isFalse();
    UUID library = insertLibrary();
    assertThatThrownBy(() -> insertJob(library, "EVENT"))
        .hasMessageContaining("chk_indexing_jobs_run_mode");
  }

  @Test
  void renamesTheSecretColumnAndKeepsItsValue() throws Exception {
    UUID library = insertLibrary();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE knowledge_libraries SET source_confluence_webhook_secret = ? WHERE id = ?")) {
      statement.setString(1, "enc:v1:geheim");
      statement.setObject(2, library);
      statement.executeUpdate();
    }

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(columnExists("knowledge_libraries", "source_confluence_webhook_secret")).isFalse();
    assertThat(columnExists("knowledge_libraries", "source_webhook_secret")).isTrue();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT source_webhook_secret FROM knowledge_libraries WHERE id = ?")) {
      statement.setObject(1, library);
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        assertThat(rs.getString(1)).isEqualTo("enc:v1:geheim");
      }
    }
  }

  @Test
  void theRunModeCheckLearnsEventAndStillRejectsAnythingElse() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID library = insertLibrary();

    assertThatCode(() -> insertJob(library, "EVENT")).doesNotThrowAnyException();
    assertThatCode(() -> insertJob(library, "FULL")).doesNotThrowAnyException();
    assertThatThrownBy(() -> insertJob(library, "PUSH"))
        .hasMessageContaining("chk_indexing_jobs_run_mode");
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private UUID insertLibrary() throws SQLException {
    UUID id = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "INSERT INTO users (id, subject, issuer, display_name, system_role, organization_id)"
              + " VALUES ('"
              + owner
              + "', 'u-"
              + owner
              + "', 'https://issuer.example', 'Test', 'USER', '"
              + ORGANIZATION
              + "')");
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type, owner_user_id,"
                + " visibility, listed, source_type, source_insecure_ssl, created_at, updated_at)"
                + " VALUES (?, '"
                + ORGANIZATION
                + "', ?, 'USER', ?, 'PRIVATE', false, 'UPLOAD', false, now(), now())")) {
      statement.setObject(1, id);
      statement.setString(2, "Bibliothek " + id);
      statement.setObject(3, owner);
      statement.executeUpdate();
    }
    return id;
  }

  private void insertJob(UUID libraryId, String runMode) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO indexing_jobs (id, status, last_progress_at, organization_id, library_id,"
                + " run_mode) VALUES (?, 'COMPLETED', now(), '"
                + ORGANIZATION
                + "', ?, ?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, libraryId);
      statement.setString(3, runMode);
      statement.executeUpdate();
    }
  }

  private boolean columnExists(String table, String column) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND"
                + " table_name = ? AND column_name = ?")) {
      statement.setString(1, table);
      statement.setString(2, column);
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        return rs.getInt(1) > 0;
      }
    }
  }
}
