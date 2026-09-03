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
 * Delta tests for {@code changes/014-confluence-webhooks.yaml} (#1140): the per-library webhook
 * secret column on {@code knowledge_libraries} and the widened {@code
 * chk_indexing_jobs_triggered_by}. The baseline fixture is brought through 010 and 013 first,
 * because 014 builds on the CONFLUENCE source type and the run mode they introduced.
 */
class Migration014ConfluenceWebhooksTest extends AbstractMigrationTest {

  private static final String CHANGELOG_010 =
      "db/changelog/changes/010-confluence-source-type.yaml";
  private static final String CHANGELOG_011 = "db/changelog/changes/013-confluence-full-sync.yaml";
  private static final String CHANGELOG_PATH = "db/changelog/changes/014-confluence-webhooks.yaml";
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
    applyChangelog(connection, CHANGELOG_010);
    applyChangelog(connection, CHANGELOG_011);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void beforeTheMigrationThereIsNoSecretColumnAndWebhookIsNotAnAllowedTrigger() throws Exception {
    UUID library = insertLibrary();
    assertThat(columnType("knowledge_libraries", "source_confluence_webhook_secret")).isNull();
    assertThatThrownBy(() -> insertJob(library, "WEBHOOK"))
        .hasMessageContaining("chk_indexing_jobs_triggered_by");
  }

  @Test
  void addsANullableEncryptedWidthSecretColumn() throws Exception {
    UUID library = insertLibrary();
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(columnType("knowledge_libraries", "source_confluence_webhook_secret"))
        .isEqualTo("character varying");
    assertThat(webhookSecret(library)).as("existing rows carry no secret").isNull();
    setWebhookSecret(library, "enc:v1:" + "x".repeat(2900));
    assertThat(webhookSecret(library)).startsWith("enc:v1:");
    setWebhookSecret(library, null);
    assertThat(webhookSecret(library)).isNull();
  }

  @Test
  void allowsWebhookAsTriggerSourceAndStillRejectsUnknownValues() throws Exception {
    UUID library = insertLibrary();
    UUID existing = insertJob(library, "SCHEDULED");
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(triggeredBy(existing)).as("existing rows untouched").isEqualTo("SCHEDULED");
    assertThatCode(() -> insertJob(library, "WEBHOOK")).doesNotThrowAnyException();
    assertThatCode(() -> insertJob(library, "MANUAL")).doesNotThrowAnyException();
    assertThatThrownBy(() -> insertJob(library, "AUTOMATION"))
        .hasMessageContaining("chk_indexing_jobs_triggered_by");
  }

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
                + " visibility, listed, source_type, source_url, source_credentials,"
                + " source_insecure_ssl, source_confluence_edition, created_at, updated_at)"
                + " VALUES (?, '"
                + ORGANIZATION
                + "', ?, 'USER', ?, 'CONFLUENCE', 'https://wiki.example.org', 'enc:v1:abc', false,"
                + " 'DATA_CENTER', now(), now())")) {
      statement.setObject(1, id);
      statement.setString(2, "Wiki " + id);
      statement.setObject(3, owner);
      statement.executeUpdate();
    }
    return id;
  }

  private UUID insertJob(UUID libraryId, String triggeredBy) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO indexing_jobs (id, status, last_progress_at, organization_id, library_id,"
                + " triggered_by) VALUES (?, 'COMPLETED', now(), '"
                + ORGANIZATION
                + "', ?, ?)")) {
      statement.setObject(1, id);
      statement.setObject(2, libraryId);
      statement.setString(3, triggeredBy);
      statement.executeUpdate();
    }
    return id;
  }

  private String triggeredBy(UUID jobId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT triggered_by FROM indexing_jobs WHERE id = ?")) {
      statement.setObject(1, jobId);
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private String webhookSecret(UUID libraryId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT source_confluence_webhook_secret FROM knowledge_libraries WHERE id = ?")) {
      statement.setObject(1, libraryId);
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private void setWebhookSecret(UUID libraryId, String secret) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE knowledge_libraries SET source_confluence_webhook_secret = ? WHERE id = ?")) {
      statement.setString(1, secret);
      statement.setObject(2, libraryId);
      statement.executeUpdate();
    }
  }

  private String columnType(String table, String column) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT data_type FROM information_schema.columns WHERE table_schema = 'public'"
                + " AND table_name = ? AND column_name = ?")) {
      statement.setString(1, table);
      statement.setString(2, column);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }
}
