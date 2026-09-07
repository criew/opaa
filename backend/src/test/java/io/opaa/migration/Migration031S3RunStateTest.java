package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Delta tests for {@code changes/031-s3-run-state.yaml} (ADR-0027, #1380): the per-library {@code
 * s3_sync_state} table with its uniqueness and cascade, and the nullable {@code bytes_downloaded}
 * column on {@code indexing_jobs}. Against the baseline; the S3 source type itself (030) is not
 * needed, the state row references the library alone.
 */
class Migration031S3RunStateTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH = "db/changelog/changes/031-s3-run-state.yaml";
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
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void beforeTheMigrationNeitherTheStateTableNorTheBytesColumnExist() throws Exception {
    assertThat(tableExists("s3_sync_state")).isFalse();
    assertThat(columnExists("indexing_jobs", "bytes_downloaded")).isFalse();
  }

  @Test
  void createsOneSyncStateRowPerLibraryThatDisappearsWithIt() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID library = insertLibrary();

    assertThat(tableExists("s3_sync_state")).isTrue();
    insertSyncState(library, "dokumente/2025/\nsatzungen");
    assertThatThrownBy(() -> insertSyncState(library, "archiv"))
        .hasMessageContaining("uk_s3_sync_state_library");
    assertThatThrownBy(() -> insertSyncState(UUID.randomUUID(), null))
        .hasMessageContaining("fk_s3_sync_state_library");
    assertThat(countSyncStates(library)).isEqualTo(1);

    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("DELETE FROM knowledge_libraries WHERE id = '" + library + "'");
    }
    assertThat(countSyncStates(library)).as("ON DELETE CASCADE").isZero();
  }

  @Test
  void addsANullableBytesDownloadedColumnToIndexingJobs() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID library = insertLibrary();

    assertThat(columnExists("indexing_jobs", "bytes_downloaded")).isTrue();
    UUID job = insertJob(library);
    assertThat(bytesDownloaded(job)).as("existing rows recorded no figure").isNull();
    try (PreparedStatement statement =
        connection.prepareStatement("UPDATE indexing_jobs SET bytes_downloaded = ? WHERE id = ?")) {
      statement.setLong(1, 7_000_000_000L);
      statement.setObject(2, job);
      statement.executeUpdate();
    }
    assertThat(bytesDownloaded(job)).as("bigint, beyond an int").isEqualTo(7_000_000_000L);
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

  private Long bytesDownloaded(UUID jobId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT bytes_downloaded FROM indexing_jobs WHERE id = ?")) {
      statement.setObject(1, jobId);
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        long value = rs.getLong(1);
        return rs.wasNull() ? null : value;
      }
    }
  }

  private void insertSyncState(UUID libraryId, String completedScopeKeys) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO s3_sync_state (id, library_id, completed_scope_keys, updated_at)"
                + " VALUES (?, ?, ?, now())")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, libraryId);
      statement.setString(3, completedScopeKeys);
      statement.executeUpdate();
    }
  }

  private int countSyncStates(UUID libraryId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT count(*) FROM s3_sync_state WHERE library_id = ?")) {
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
