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
 * Delta tests for {@code changes/014-confluence-full-sync.yaml} (ADR-0023, Entscheidung 4): the run
 * mode on {@code indexing_jobs} with its backfill from the library's source type, the two context
 * columns on {@code documents}, and the per-library {@code confluence_sync_state} table with its
 * uniqueness and cascade. The baseline fixture is brought to the state 010 leaves behind first,
 * because 014 builds on the CONFLUENCE source type 010 introduced.
 */
class Migration014ConfluenceFullSyncTest extends AbstractMigrationTest {

  private static final String CHANGELOG_010 =
      "db/changelog/changes/010-confluence-source-type.yaml";
  private static final String CHANGELOG_PATH = "db/changelog/changes/014-confluence-full-sync.yaml";
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
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void beforeTheMigrationNeitherRunModeNorContextColumnsNorSyncStateExist() throws Exception {
    assertThat(columnType("indexing_jobs", "run_mode")).isNull();
    assertThat(columnType("documents", "source_container_key")).isNull();
    assertThat(columnType("documents", "source_hierarchy_path")).isNull();
    assertThat(tableExists("confluence_sync_state")).isFalse();
  }

  @Test
  void backfillsTheRunModeFromTheLibrarySourceTypeAndBindsNewValues() throws Exception {
    UUID rss = insertLibrary("RSS_FEED", "https://feed.example.org/rss", null, null);
    UUID web = insertLibrary("HTTP_DIRECTORY", "https://docs.example.org/", null, null);
    UUID rssJob = insertJob(rss);
    UUID webJob = insertJob(web);
    UUID orphanJob = insertJob(null);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(runMode(rssJob)).isEqualTo("INCREMENTAL");
    assertThat(runMode(webJob)).isEqualTo("FULL");
    assertThat(runMode(orphanJob)).isEqualTo("FULL");
    assertThat(runMode(insertJob(web))).as("default for a new row").isEqualTo("FULL");
    assertThatThrownBy(() -> setRunMode(webJob, "PARTIAL"))
        .hasMessageContaining("chk_indexing_jobs_run_mode");
    assertThatCode(() -> setRunMode(webJob, "INCREMENTAL")).doesNotThrowAnyException();
  }

  @Test
  void addsNullableContextColumnsToDocuments() throws Exception {
    UUID library =
        insertLibrary("CONFLUENCE", "https://wiki.example.org", "enc:v1:abc", "DATA_CENTER");
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(columnType("documents", "source_container_key")).isEqualTo("character varying");
    assertThat(columnType("documents", "source_hierarchy_path")).isEqualTo("character varying");
    UUID withContext = insertDocument(library, "ENG", "Handbuch / Kapitel 1");
    UUID without = insertDocument(library, null, null);
    assertThat(documentContext(withContext)).containsExactly("ENG", "Handbuch / Kapitel 1");
    assertThat(documentContext(without)).containsExactly(null, null);
  }

  @Test
  void createsOneSyncStateRowPerLibraryThatDisappearsWithIt() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID library =
        insertLibrary("CONFLUENCE", "https://wiki.example.org", "enc:v1:abc", "DATA_CENTER");

    assertThat(tableExists("confluence_sync_state")).isTrue();
    insertSyncState(library, "ENG\nHR");
    assertThatThrownBy(() -> insertSyncState(library, "IT"))
        .hasMessageContaining("uk_confluence_sync_state_library");
    assertThatThrownBy(() -> insertSyncState(UUID.randomUUID(), null))
        .hasMessageContaining("fk_confluence_sync_state_library");
    assertThat(countSyncStates(library)).isEqualTo(1);

    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("DELETE FROM knowledge_libraries WHERE id = '" + library + "'");
    }
    assertThat(countSyncStates(library)).as("ON DELETE CASCADE").isZero();
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private UUID insertLibrary(
      String sourceType, String sourceUrl, String credentials, String edition) throws SQLException {
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
                + "', ?, 'USER', ?, 'PRIVATE', false, ?, ?, ?, false, ?, now(), now())")) {
      statement.setObject(1, id);
      statement.setString(2, sourceType + " " + id);
      statement.setObject(3, owner);
      statement.setString(4, sourceType);
      statement.setString(5, sourceUrl);
      statement.setString(6, credentials);
      statement.setString(7, edition);
      statement.executeUpdate();
    }
    return id;
  }

  private UUID insertJob(UUID libraryId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO indexing_jobs (id, status, last_progress_at, organization_id, library_id)"
                + " VALUES (?, 'COMPLETED', now(), '"
                + ORGANIZATION
                + "', ?)")) {
      statement.setObject(1, id);
      statement.setObject(2, libraryId);
      statement.executeUpdate();
    }
    return id;
  }

  private String runMode(UUID jobId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT run_mode FROM indexing_jobs WHERE id = ?")) {
      statement.setObject(1, jobId);
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private void setRunMode(UUID jobId, String runMode) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("UPDATE indexing_jobs SET run_mode = ? WHERE id = ?")) {
      statement.setString(1, runMode);
      statement.setObject(2, jobId);
      statement.executeUpdate();
    }
  }

  private UUID insertDocument(UUID libraryId, String containerKey, String hierarchyPath)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO documents (id, file_name, file_path, content_type, file_size, chunk_count,"
                + " indexed_at, status, source_type, library_id, organization_id, created_at,"
                + " source_container_key, source_hierarchy_path)"
                + " VALUES (?, 'Seite', ?, 'text/html', 0, 0, now(), 'INDEXED', 'CONFLUENCE', ?, '"
                + ORGANIZATION
                + "', now(), ?, ?)")) {
      statement.setObject(1, id);
      statement.setString(2, "https://wiki.example.org/pages/viewpage.action?pageId=" + id);
      statement.setObject(3, libraryId);
      statement.setString(4, containerKey);
      statement.setString(5, hierarchyPath);
      statement.executeUpdate();
    }
    return id;
  }

  private java.util.List<String> documentContext(UUID documentId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT source_container_key, source_hierarchy_path FROM documents WHERE id = ?")) {
      statement.setObject(1, documentId);
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        return java.util.Arrays.asList(rs.getString(1), rs.getString(2));
      }
    }
  }

  private void insertSyncState(UUID libraryId, String completedSpaceKeys) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO confluence_sync_state (id, library_id, completed_space_keys, updated_at)"
                + " VALUES (?, ?, ?, now())")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, libraryId);
      statement.setString(3, completedSpaceKeys);
      statement.executeUpdate();
    }
  }

  private int countSyncStates(UUID libraryId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT count(*) FROM confluence_sync_state WHERE library_id = ?")) {
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
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND"
                + " table_name = ?")) {
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
            "SELECT data_type FROM information_schema.columns WHERE table_schema = 'public' AND"
                + " table_name = ? AND column_name = ?")) {
      statement.setString(1, table);
      statement.setString(2, column);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }
}
