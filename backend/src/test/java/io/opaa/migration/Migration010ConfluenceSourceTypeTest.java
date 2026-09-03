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
 * Delta tests for {@code changes/010-confluence-source-type.yaml} (ADR-0023): every changeset is
 * exercised against the state the baseline leaves behind - the widened value lists on {@code
 * documents} and {@code knowledge_libraries}, the new edition column, the CONFLUENCE arm of the
 * source-configuration constraint (and the edition being bound to NULL for every other type), and
 * the space-selection table with its per-library key uniqueness and cascade.
 */
class Migration010ConfluenceSourceTypeTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/010-confluence-source-type.yaml";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
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
  void beforeTheMigrationConfluenceIsRejectedAsLibraryAndDocumentSourceType() throws Exception {
    assertThatThrownBy(
            () ->
                insertLibrary(
                    "CONFLUENCE", "https://wiki.example.org", "enc:v1:abc", null, "pre-migration"))
        .hasMessageContaining("chk_knowledge_libraries_source");
    UUID filesystem = insertLibraryWithPath("FILESYSTEM", "/srv/docs", null, null, null, "fs");
    assertThatThrownBy(() -> insertDocument(filesystem, "CONFLUENCE"))
        .hasMessageContaining("chk_documents_source_type");
    assertThat(tableExists("knowledge_library_confluence_spaces")).isFalse();
    assertThat(columnType("knowledge_libraries", "source_confluence_edition")).isNull();
  }

  @Test
  void widensBothSourceTypeValueListsToConfluence() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    UUID libraryId =
        insertLibrary("CONFLUENCE", "https://wiki.example.org", "enc:v1:abc", "DATA_CENTER", "ok");
    assertThat(libraryId).isNotNull();
    assertThatCode(() -> insertDocument(libraryId, "CONFLUENCE")).doesNotThrowAnyException();

    assertThatThrownBy(() -> insertDocument(libraryId, "JIRA"))
        .hasMessageContaining("chk_documents_source_type");
    assertThatThrownBy(() -> insertLibrary("JIRA", null, null, null, "bogus"))
        .hasMessageContaining("chk_knowledge_libraries_source_type");
  }

  @Test
  void addsTheEditionColumnBoundToItsTwoValues() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(columnType("knowledge_libraries", "source_confluence_edition"))
        .isEqualTo("character varying");
    assertThatThrownBy(
            () ->
                insertLibrary(
                    "CONFLUENCE",
                    "https://wiki.example.org",
                    "enc:v1:abc",
                    "SERVER",
                    "bad edition"))
        .hasMessageContaining("chk_knowledge_libraries_confluence_edition");
    assertThatCode(
            () ->
                insertLibrary(
                    "CONFLUENCE", "https://x.atlassian.net", "enc:v1:abc", "CLOUD", "cloud ok"))
        .doesNotThrowAnyException();
  }

  @Test
  void confluenceArmRequiresUrlCredentialsAndEditionAndForbidsAPath() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(
            () -> insertLibrary("CONFLUENCE", null, "enc:v1:abc", "DATA_CENTER", "no url"))
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
    assertThatThrownBy(
            () ->
                insertLibrary(
                    "CONFLUENCE",
                    "https://wiki.example.org",
                    null,
                    "DATA_CENTER",
                    "no credentials"))
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
    assertThatThrownBy(
            () ->
                insertLibrary(
                    "CONFLUENCE", "https://wiki.example.org", "enc:v1:abc", null, "no edition"))
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
    assertThatThrownBy(
            () ->
                insertLibraryWithPath(
                    "CONFLUENCE",
                    "/srv/docs",
                    "https://wiki.example.org",
                    "enc:v1:abc",
                    "DATA_CENTER"))
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
  }

  @Test
  void otherSourceTypesMustNotCarryAnEditionAndKeepTheirBaselineShape() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(() -> insertLibrary("UPLOAD", null, null, "CLOUD", "upload with edition"))
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
    assertThatThrownBy(
            () ->
                insertLibrary(
                    "RSS_FEED",
                    "https://example.org/feed.xml",
                    null,
                    "DATA_CENTER",
                    "rss w/ edition"))
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
    assertThatCode(() -> insertLibrary("UPLOAD", null, null, null, "upload"))
        .doesNotThrowAnyException();
    assertThatCode(
            () -> insertLibrary("RSS_FEED", "https://example.org/feed.xml", null, null, "rss"))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                insertLibrary(
                    "HTTP_DIRECTORY", "https://files.example.org/", "enc:v1:abc", null, "web"))
        .doesNotThrowAnyException();
    assertThatCode(() -> insertLibraryWithPath("FILESYSTEM", "/srv/docs", null, null, null, "fs"))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () -> insertLibraryWithPath("FILESYSTEM", "/srv/docs", null, null, "CLOUD", "fs+ed"))
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
  }

  @Test
  void spaceSelectionTableIsKeyedPerLibraryAndCascadesWithIt() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID first =
        insertLibrary("CONFLUENCE", "https://wiki.example.org", "enc:v1:abc", "DATA_CENTER", "one");
    UUID second =
        insertLibrary("CONFLUENCE", "https://wiki.example.org", "enc:v1:xyz", "DATA_CENTER", "two");

    insertSpace(first, "ENG", "Engineering");
    insertSpace(first, "HR", null);
    // the same key in a second library against the same instance is a different selection
    insertSpace(second, "ENG", "Engineering");

    assertThatThrownBy(() -> insertSpace(first, "ENG", "Engineering again"))
        .hasMessageContaining("pk_knowledge_library_confluence_spaces");
    assertThat(countSpaces(first)).isEqualTo(2);
    assertThat(countSpaces(second)).isEqualTo(1);

    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("DELETE FROM knowledge_libraries WHERE id = '" + first + "'");
    }
    assertThat(countSpaces(first)).isZero();
    assertThat(countSpaces(second)).isEqualTo(1);
  }

  @Test
  void twoLibrariesMayShareAddressAndCredentials() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    // ADR-0023, Entscheidung 5: no uniqueness on address or token
    assertThatCode(
            () -> {
              insertLibrary(
                  "CONFLUENCE", "https://wiki.example.org", "enc:v1:same", "DATA_CENTER", "a");
              insertLibrary(
                  "CONFLUENCE", "https://wiki.example.org", "enc:v1:same", "DATA_CENTER", "b");
            })
        .doesNotThrowAnyException();
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private UUID insertLibrary(
      String sourceType, String sourceUrl, String credentials, String edition, String name)
      throws SQLException {
    return insertLibraryWithPath(sourceType, null, sourceUrl, credentials, edition, name);
  }

  private UUID insertLibraryWithPath(
      String sourceType, String sourcePath, String sourceUrl, String credentials, String edition)
      throws SQLException {
    return insertLibraryWithPath(
        sourceType, sourcePath, sourceUrl, credentials, edition, "with path");
  }

  private UUID insertLibraryWithPath(
      String sourceType,
      String sourcePath,
      String sourceUrl,
      String credentials,
      String edition,
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
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type, owner_user_id,"
                + " visibility, listed, source_type, source_path, source_url, source_credentials,"
                + " source_insecure_ssl, source_confluence_edition, created_at, updated_at)"
                + " VALUES (?, '00000000-0000-0000-0000-000000000001', ?, 'USER', ?, 'PRIVATE',"
                + " false, ?, ?, ?, ?, false, ?, now(), now())")) {
      statement.setObject(1, id);
      statement.setString(2, name + " " + id);
      statement.setObject(3, owner);
      statement.setString(4, sourceType);
      statement.setString(5, sourcePath);
      statement.setString(6, sourceUrl);
      statement.setString(7, credentials);
      statement.setString(8, edition);
      statement.executeUpdate();
    } catch (SQLException e) {
      if (e.getMessage().contains("source_confluence_edition")
          && e.getMessage().contains("does not exist")) {
        // pre-migration path: insert without the column so the constraint check is what fails
        return insertLibraryPreMigration(
            id, owner, sourceType, sourcePath, sourceUrl, credentials, name);
      }
      throw e;
    }
    return id;
  }

  private UUID insertLibraryPreMigration(
      UUID id,
      UUID owner,
      String sourceType,
      String sourcePath,
      String sourceUrl,
      String credentials,
      String name)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type, owner_user_id,"
                + " visibility, listed, source_type, source_path, source_url, source_credentials,"
                + " source_insecure_ssl, created_at, updated_at)"
                + " VALUES (?, '00000000-0000-0000-0000-000000000001', ?, 'USER', ?, 'PRIVATE',"
                + " false, ?, ?, ?, ?, false, now(), now())")) {
      statement.setObject(1, id);
      statement.setString(2, name + " " + id);
      statement.setObject(3, owner);
      statement.setString(4, sourceType);
      statement.setString(5, sourcePath);
      statement.setString(6, sourceUrl);
      statement.setString(7, credentials);
      statement.executeUpdate();
    }
    return id;
  }

  private void insertDocument(UUID libraryId, String sourceType) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO documents (id, file_name, file_path, content_type, file_size, chunk_count,"
                + " indexed_at, status, source_type, library_id, organization_id, created_at)"
                + " VALUES (?, 'Seite', ?, 'text/html', 0, 0, now(), 'INDEXED', ?, ?,"
                + " '00000000-0000-0000-0000-000000000001', now())")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setString(
          2, "https://wiki.example.org/pages/viewpage.action?pageId=" + UUID.randomUUID());
      statement.setString(3, sourceType);
      statement.setObject(4, libraryId);
      statement.executeUpdate();
    }
  }

  private void insertSpace(UUID libraryId, String key, String name) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO knowledge_library_confluence_spaces (library_id, space_key, space_name)"
                + " VALUES (?, ?, ?)")) {
      statement.setObject(1, libraryId);
      statement.setString(2, key);
      statement.setString(3, name);
      statement.executeUpdate();
    }
  }

  private int countSpaces(UUID libraryId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT count(*) FROM knowledge_library_confluence_spaces WHERE library_id = ?")) {
      statement.setObject(1, libraryId);
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private boolean tableExists(String table) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT count(*) FROM information_schema.tables WHERE table_name = ?")) {
      statement.setString(1, table);
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        return rs.getInt(1) > 0;
      }
    }
  }

  private String columnType(String table, String column) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT data_type FROM information_schema.columns WHERE table_name = ? AND column_name"
                + " = ?")) {
      statement.setString(1, table);
      statement.setString(2, column);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }
}
