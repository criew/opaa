package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/030-s3-source-type.yaml} (ADR-0027): against the state migration
 * 010 leaves behind - the widened value lists on {@code documents} and {@code knowledge_libraries},
 * the new {@code source_settings} column, the S3 arm of the source-configuration constraint with
 * its non-empty-scopes guard, and {@code source_settings} being bound to NULL for every other type.
 */
class Migration030S3SourceTypeTest extends AbstractMigrationTest {

  private static final String CONFLUENCE_CHANGELOG =
      "db/changelog/changes/010-confluence-source-type.yaml";
  private static final String CHANGELOG_PATH = "db/changelog/changes/030-s3-source-type.yaml";
  private static final String SETTINGS =
      "{\"region\":\"eu-central-1\",\"pathStyle\":true,"
          + "\"scopes\":[{\"bucket\":\"dokumente\",\"prefix\":\"2025/\"}]}";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    // the constraint 030 recreates is the one 010 shaped (edition column, CONFLUENCE arm)
    applyChangelog(connection, CONFLUENCE_CHANGELOG);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void beforeTheMigrationS3IsRejectedAsLibraryAndDocumentSourceType() throws Exception {
    assertThat(columnType("knowledge_libraries", "source_settings")).isNull();
    assertThatThrownBy(
            () -> insertLibrary("S3", "https://s3.example.org", "enc:v1:abc", null, "pre"))
        .hasMessageContaining("chk_knowledge_libraries_source_");
    UUID upload = insertLibrary("UPLOAD", null, null, null, "upload");
    assertThatThrownBy(() -> insertDocument(upload, "S3"))
        .hasMessageContaining("chk_documents_source_type");
  }

  @Test
  void widensBothSourceTypeValueListsAndAddsTheSettingsColumn() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(columnType("knowledge_libraries", "source_settings")).isEqualTo("jsonb");
    UUID libraryId = insertLibrary("S3", "https://s3.example.org", "enc:v1:abc", SETTINGS, "ok");
    assertThat(libraryId).isNotNull();
    assertThatCode(() -> insertDocument(libraryId, "S3")).doesNotThrowAnyException();
    assertThatThrownBy(() -> insertDocument(libraryId, "GCS"))
        .hasMessageContaining("chk_documents_source_type");
  }

  @Test
  void s3ArmRequiresUrlCredentialsAndNonEmptyScopesAndForbidsPathAndEdition() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(() -> insertLibrary("S3", null, "enc:v1:abc", SETTINGS, "no url"))
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
    assertThatThrownBy(
            () -> insertLibrary("S3", "https://s3.example.org", null, SETTINGS, "no credentials"))
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
    assertThatThrownBy(
            () -> insertLibrary("S3", "https://s3.example.org", "enc:v1:abc", null, "no settings"))
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
    assertThatThrownBy(
            () ->
                insertLibrary(
                    "S3", "https://s3.example.org", "enc:v1:abc", "{\"scopes\":[]}", "empty"))
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
    assertThatThrownBy(
            () ->
                insertLibrary(
                    "S3", "https://s3.example.org", "enc:v1:abc", "{\"region\":\"x\"}", "none"))
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
    assertThatThrownBy(
            () ->
                insertLibrary(
                    "S3",
                    "https://s3.example.org",
                    "enc:v1:abc",
                    "{\"scopes\":\"dokumente\"}",
                    "not an array"))
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
    assertThatThrownBy(
            () ->
                insertLibraryFull(
                    "S3",
                    "/srv/docs",
                    "https://s3.example.org",
                    "enc:v1:abc",
                    null,
                    SETTINGS,
                    "with path"))
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
    assertThatThrownBy(
            () ->
                insertLibraryFull(
                    "S3",
                    null,
                    "https://s3.example.org",
                    "enc:v1:abc",
                    "CLOUD",
                    SETTINGS,
                    "with edition"))
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
  }

  @Test
  void otherSourceTypesMustNotCarrySettingsAndKeepTheirShape() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(() -> insertLibrary("UPLOAD", null, null, SETTINGS, "upload+settings"))
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
    assertThatThrownBy(
            () ->
                insertLibrary(
                    "HTTP_DIRECTORY", "https://files.example.org/", null, SETTINGS, "web+settings"))
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
    assertThatThrownBy(
            () ->
                insertLibraryFull(
                    "CONFLUENCE",
                    null,
                    "https://wiki.example.org",
                    "enc:v1:abc",
                    "DATA_CENTER",
                    SETTINGS,
                    "wiki+settings"))
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
    assertThatCode(() -> insertLibrary("UPLOAD", null, null, null, "upload"))
        .doesNotThrowAnyException();
    assertThatCode(
            () -> insertLibrary("RSS_FEED", "https://example.org/feed.xml", null, null, "rss"))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                insertLibraryFull(
                    "CONFLUENCE",
                    null,
                    "https://wiki.example.org",
                    "enc:v1:abc",
                    "DATA_CENTER",
                    null,
                    "wiki"))
        .doesNotThrowAnyException();
    assertThatCode(() -> insertLibraryFull("FILESYSTEM", "/srv/docs", null, null, null, null, "fs"))
        .doesNotThrowAnyException();
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private UUID insertLibrary(
      String sourceType, String sourceUrl, String credentials, String settings, String name)
      throws SQLException {
    return insertLibraryFull(sourceType, null, sourceUrl, credentials, null, settings, name);
  }

  private UUID insertLibraryFull(
      String sourceType,
      String sourcePath,
      String sourceUrl,
      String credentials,
      String edition,
      String settings,
      String name)
      throws SQLException {
    UUID id = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "INSERT INTO users (id, subject, issuer, display_name, system_role, organization_id)"
              + " VALUES ('"
              + owner
              + "', 'u-"
              + owner
              + "', 'https://issuer.example', 'Test', 'USER',"
              + " '00000000-0000-0000-0000-000000000001')");
    }
    boolean settingsColumn = columnType("knowledge_libraries", "source_settings") != null;
    String columns =
        "id, organization_id, name, owner_type, owner_user_id, visibility, listed, source_type,"
            + " source_path, source_url, source_credentials, source_insecure_ssl,"
            + " source_confluence_edition, created_at, updated_at"
            + (settingsColumn ? ", source_settings" : "");
    String values =
        "?, '00000000-0000-0000-0000-000000000001', ?, 'USER', ?, 'PRIVATE', false, ?, ?, ?, ?,"
            + " false, ?, now(), now()"
            + (settingsColumn ? ", ?::jsonb" : "");
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO knowledge_libraries (" + columns + ") VALUES (" + values + ")")) {
      statement.setObject(1, id);
      statement.setString(2, name + " " + id);
      statement.setObject(3, owner);
      statement.setString(4, sourceType);
      statement.setString(5, sourcePath);
      statement.setString(6, sourceUrl);
      statement.setString(7, credentials);
      statement.setString(8, edition);
      if (settingsColumn) {
        statement.setString(9, settings);
      }
      statement.executeUpdate();
    }
    return id;
  }

  private void insertDocument(UUID libraryId, String sourceType) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO documents (id, file_name, file_path, content_type, file_size, chunk_count,"
                + " indexed_at, status, source_type, library_id, organization_id, created_at)"
                + " VALUES (?, 'sitzung.pdf', ?, 'application/pdf', 0, 0, now(), 'INDEXED', ?, ?,"
                + " '00000000-0000-0000-0000-000000000001', now())")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setString(2, "s3://dokumente/2025/" + UUID.randomUUID() + ".pdf");
      statement.setString(3, sourceType);
      statement.setObject(4, libraryId);
      statement.executeUpdate();
    }
  }

  private String columnType(String table, String column) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT data_type FROM information_schema.columns WHERE table_name = ? AND column_name"
                + " = ?")) {
      statement.setString(1, table);
      statement.setString(2, column);
      try (var rs = statement.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }
}
