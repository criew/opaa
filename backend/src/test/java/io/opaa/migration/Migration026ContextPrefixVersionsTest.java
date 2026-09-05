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
 * Delta test for {@code changes/026-context-prefix-versions.yaml} (#1072): the version pair the
 * Kontextpräfix-Nachlauf selects by. An existing library starts at version 1 with both core-field
 * Wirkstellen off - the Wirkstelle is a deliberate decision per field, never a default for all -
 * and an existing document reads {@code NULL}, which is what puts the whole Altbestand into the
 * run's selection.
 */
class Migration026ContextPrefixVersionsTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/026-context-prefix-versions.yaml";
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
  void stampsLibrariesWithVersionOneAndLeavesEveryExistingDocumentPending() throws Exception {
    assertThat(columnType("knowledge_libraries", "context_prefix_version")).isNull();
    UUID libraryId = insertLibrary();
    UUID documentId = insertDocument(libraryId);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(columnType("knowledge_libraries", "context_prefix_version")).isEqualTo("integer");
    assertThat(columnType("knowledge_libraries", "core_context_prefix_document_type"))
        .isEqualTo("boolean");
    assertThat(columnType("knowledge_libraries", "core_context_prefix_document_date"))
        .isEqualTo("boolean");
    assertThat(columnType("documents", "context_prefix_version")).isEqualTo("integer");

    assertThat(libraryVersion(libraryId)).isEqualTo(1);
    assertThat(coreFlag(libraryId, "core_context_prefix_document_type")).isFalse();
    assertThat(coreFlag(libraryId, "core_context_prefix_document_date")).isFalse();
    assertThat(documentVersion(documentId))
        .as("the Altbestand was never embedded under a prefix version and is therefore pending")
        .isNull();

    assertThatThrownBy(() -> setLibraryVersion(libraryId, 0))
        .hasMessageContaining("chk_knowledge_libraries_context_prefix_version");
  }

  private String columnType(String table, String column) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT data_type FROM information_schema.columns WHERE table_name = ? AND"
                + " column_name = ?")) {
      statement.setString(1, table);
      statement.setString(2, column);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  private int libraryVersion(UUID libraryId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT context_prefix_version FROM knowledge_libraries WHERE id = ?")) {
      statement.setObject(1, libraryId);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getInt(1);
      }
    }
  }

  private boolean coreFlag(UUID libraryId, String column) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT " + column + " FROM knowledge_libraries WHERE id = ?")) {
      statement.setObject(1, libraryId);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getBoolean(1);
      }
    }
  }

  private Integer documentVersion(UUID documentId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT context_prefix_version FROM documents WHERE id = ?")) {
      statement.setObject(1, documentId);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        int value = rs.getInt(1);
        return rs.wasNull() ? null : value;
      }
    }
  }

  private void setLibraryVersion(UUID libraryId, int version) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE knowledge_libraries SET context_prefix_version = ? WHERE id = ?")) {
      statement.setInt(1, version);
      statement.setObject(2, libraryId);
      statement.executeUpdate();
    }
  }

  private UUID insertLibrary() throws SQLException {
    UUID ownerId = insertUser();
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type,"
                + " owner_user_id, visibility, listed, source_type) VALUES (?, ?, ?, 'USER', ?,"
                + " 'PRIVATE', false, 'UPLOAD')")) {
      statement.setObject(1, id);
      statement.setObject(2, ORGANIZATION_ID);
      statement.setString(3, "Bestand " + id);
      statement.setObject(4, ownerId);
      statement.executeUpdate();
    }
    return id;
  }

  private UUID insertDocument(UUID libraryId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO documents (id, organization_id, library_id, file_name, file_path,"
                + " status) VALUES (?, ?, ?, 'satzung.pdf', '/tmp/satzung.pdf', 'INDEXED')")) {
      statement.setObject(1, id);
      statement.setObject(2, ORGANIZATION_ID);
      statement.setObject(3, libraryId);
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
}
