package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/033-source-sync-state.yaml} (#1399): the one {@code
 * source_sync_state} table replacing {@code confluence_sync_state} and {@code s3_sync_state} with
 * every row carried over (id, values, the anchor only from Confluence), and the listing-assessment
 * column renamed to {@code unlisted_scope_keys} with its values kept. Against the state migrations
 * 013 (Confluence state), 023 (assessment columns) and 031 (S3 state) leave behind.
 */
class Migration033SourceSyncStateTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH = "db/changelog/changes/033-source-sync-state.yaml";
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
    applyChangelog(connection, "db/changelog/changes/013-confluence-full-sync.yaml");
    applyChangelog(connection, "db/changelog/changes/023-indexing-jobs-listing-assessment.yaml");
    applyChangelog(connection, "db/changelog/changes/031-s3-run-state.yaml");
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void beforeTheMigrationTwoStateTablesAndTheOldColumnNameExist() throws Exception {
    assertThat(tableExists("confluence_sync_state")).isTrue();
    assertThat(tableExists("s3_sync_state")).isTrue();
    assertThat(tableExists("source_sync_state")).isFalse();
    assertThat(columnExists("indexing_jobs", "unreadable_space_keys")).isTrue();
    assertThat(columnExists("indexing_jobs", "unlisted_scope_keys")).isFalse();
  }

  @Test
  void carriesEveryRowOfBothOldTablesOverAndDropsThem() throws Exception {
    UUID confluenceLibrary = insertLibrary();
    UUID s3Library = insertLibrary();
    UUID confluenceRow = UUID.randomUUID();
    UUID s3Row = UUID.randomUUID();
    UUID confluenceJob = UUID.randomUUID();
    Instant anchor = Instant.parse("2026-09-01T06:00:00Z");
    Instant s3CompletedAt = Instant.parse("2026-09-06T20:00:00Z");
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO confluence_sync_state (id, library_id, full_sync_job_id,"
                + " completed_space_keys, full_sync_completed_at, incremental_anchor, updated_at)"
                + " VALUES (?, ?, ?, ?, NULL, ?, now())")) {
      statement.setObject(1, confluenceRow);
      statement.setObject(2, confluenceLibrary);
      statement.setObject(3, confluenceJob);
      statement.setString(4, "ENG\nHR");
      statement.setTimestamp(5, Timestamp.from(anchor));
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO s3_sync_state (id, library_id, full_sync_job_id, completed_scope_keys,"
                + " full_sync_completed_at, updated_at) VALUES (?, ?, NULL, NULL, ?, now())")) {
      statement.setObject(1, s3Row);
      statement.setObject(2, s3Library);
      statement.setTimestamp(3, Timestamp.from(s3CompletedAt));
      statement.executeUpdate();
    }

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(tableExists("confluence_sync_state")).isFalse();
    assertThat(tableExists("s3_sync_state")).isFalse();
    assertThat(tableExists("source_sync_state")).isTrue();
    assertThat(countSyncStates()).isEqualTo(2);

    try (ResultSet rs = syncState(confluenceLibrary)) {
      assertThat(rs.next()).as("the Confluence row survives").isTrue();
      assertThat(rs.getObject("id", UUID.class)).isEqualTo(confluenceRow);
      assertThat(rs.getObject("full_sync_job_id", UUID.class))
          .as("an interrupted full sync stays interrupted")
          .isEqualTo(confluenceJob);
      assertThat(rs.getString("completed_scope_keys")).isEqualTo("ENG\nHR");
      assertThat(rs.getTimestamp("full_sync_completed_at")).isNull();
      assertThat(rs.getTimestamp("incremental_anchor").toInstant()).isEqualTo(anchor);
    }
    try (ResultSet rs = syncState(s3Library)) {
      assertThat(rs.next()).as("the S3 row survives").isTrue();
      assertThat(rs.getObject("id", UUID.class)).isEqualTo(s3Row);
      assertThat(rs.getObject("full_sync_job_id", UUID.class)).isNull();
      assertThat(rs.getString("completed_scope_keys")).isNull();
      assertThat(rs.getTimestamp("full_sync_completed_at").toInstant()).isEqualTo(s3CompletedAt);
      assertThat(rs.getTimestamp("incremental_anchor")).as("S3 never had an anchor").isNull();
    }
  }

  @Test
  void theNewTableKeepsOneRowPerLibraryThatDisappearsWithIt() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID library = insertLibrary();

    insertSyncState(library, "dokumente/2025/\nsatzungen");
    assertThatThrownBy(() -> insertSyncState(library, "ENG"))
        .hasMessageContaining("uk_source_sync_state_library");
    assertThatThrownBy(() -> insertSyncState(UUID.randomUUID(), null))
        .hasMessageContaining("fk_source_sync_state_library");
    assertThat(countSyncStates()).isEqualTo(1);

    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("DELETE FROM knowledge_libraries WHERE id = '" + library + "'");
    }
    assertThat(countSyncStates()).as("ON DELETE CASCADE").isZero();
  }

  @Test
  void renamesTheAssessmentColumnAndKeepsItsValues() throws Exception {
    UUID library = insertLibrary();
    UUID job = insertJob(library);
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE indexing_jobs SET listing_complete = false, unreadable_space_keys = ?"
                + " WHERE id = ?")) {
      statement.setString(1, "SEC,geheim/intern/");
      statement.setObject(2, job);
      statement.executeUpdate();
    }

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(columnExists("indexing_jobs", "unreadable_space_keys")).isFalse();
    assertThat(columnExists("indexing_jobs", "unlisted_scope_keys")).isTrue();
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT unlisted_scope_keys FROM indexing_jobs WHERE id = ?")) {
      statement.setObject(1, job);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("SEC,geheim/intern/");
      }
    }
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

  private void insertSyncState(UUID libraryId, String completedScopeKeys) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO source_sync_state (id, library_id, completed_scope_keys, updated_at)"
                + " VALUES (?, ?, ?, now())")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, libraryId);
      statement.setString(3, completedScopeKeys);
      statement.executeUpdate();
    }
  }

  /** The caller closes the result set; the statement closes with it. */
  private ResultSet syncState(UUID libraryId) throws SQLException {
    PreparedStatement statement =
        connection.prepareStatement("SELECT * FROM source_sync_state WHERE library_id = ?");
    statement.closeOnCompletion();
    statement.setObject(1, libraryId);
    return statement.executeQuery();
  }

  private int countSyncStates() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT count(*) FROM source_sync_state")) {
      rs.next();
      return rs.getInt(1);
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
