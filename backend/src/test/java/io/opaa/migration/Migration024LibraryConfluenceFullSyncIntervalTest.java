package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta test for {@code changes/024-library-confluence-full-sync-interval.yaml} (#1200): the
 * nullable per-library full-sync rhythm - absent before the changeSet; after it, every existing
 * library reads {@code NULL} (instance-wide default), a positive value can be stored, and the check
 * constraint refuses zero (the rhythm is never switchable off).
 */
class Migration024LibraryConfluenceFullSyncIntervalTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/024-library-confluence-full-sync-interval.yaml";
  private static final UUID ORGANIZATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

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
  void addsANullableAlwaysPositiveColumnThatExistingLibrariesReadAsNull() throws Exception {
    assertThat(columnType()).as("column absent before the changeSet").isNull();
    UUID libraryId = insertLibrary();

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(columnType()).isEqualTo("integer");
    assertThat(rhythmOf(libraryId)).as("an existing library follows the default").isNull();
    setRhythm(libraryId, 14);
    assertThat(rhythmOf(libraryId)).isEqualTo(14);
    assertThatThrownBy(() -> setRhythm(libraryId, 0))
        .hasMessageContaining("chk_knowledge_libraries_confluence_full_sync_interval");
  }

  private String columnType() throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT data_type FROM information_schema.columns WHERE table_name ="
                + " 'knowledge_libraries' AND column_name ="
                + " 'source_confluence_full_sync_interval_days'")) {
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  private UUID insertLibrary() throws SQLException {
    UUID ownerId = insertUser();
    UUID id = UUID.randomUUID();
    // The baseline's chk_knowledge_libraries_source_type predates CONFLUENCE (changeset 010) -
    // the column under test is type-independent at the database level, so UPLOAD serves here.
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type,"
                + " owner_user_id, visibility, listed, source_type) VALUES (?, ?, ?, 'USER', ?,"
                + " 'PRIVATE', false, 'UPLOAD')")) {
      statement.setObject(1, id);
      statement.setObject(2, ORGANIZATION_ID);
      statement.setString(3, "Wiki " + id);
      statement.setObject(4, ownerId);
      statement.executeUpdate();
    }
    return id;
  }

  private UUID insertUser() throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO users (id, subject, issuer, organization_id) VALUES (?, ?, 'test', ?)")) {
      statement.setObject(1, id);
      statement.setString(2, "owner-" + id);
      statement.setObject(3, ORGANIZATION_ID);
      statement.executeUpdate();
    }
    return id;
  }

  private Integer rhythmOf(UUID libraryId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT source_confluence_full_sync_interval_days FROM knowledge_libraries WHERE id ="
                + " ?")) {
      statement.setObject(1, libraryId);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        int value = rs.getInt(1);
        return rs.wasNull() ? null : value;
      }
    }
  }

  private void setRhythm(UUID libraryId, int days) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE knowledge_libraries SET source_confluence_full_sync_interval_days = ? WHERE id"
                + " = ?")) {
      statement.setInt(1, days);
      statement.setObject(2, libraryId);
      statement.executeUpdate();
    }
  }
}
