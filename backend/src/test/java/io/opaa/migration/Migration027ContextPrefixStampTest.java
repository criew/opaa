package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta test for {@code changes/027-context-prefix-stamp.yaml} (#1072): what the
 * Kontextpräfix-Nachlauf selects by. An existing library starts with both switchable core-field
 * Wirkstellen off - the Wirkstelle is a deliberate decision per field, never a default for all -
 * and an existing document reads {@code NULL}, which is what puts the whole Altbestand into the
 * run's selection until it has been embedded once under a recorded prefix.
 */
class Migration027ContextPrefixStampTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH = "db/changelog/changes/027-context-prefix-stamp.yaml";
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
  void addsTheStampAndTheCoreWirkstellenAndLeavesEveryExistingDocumentPending() throws Exception {
    assertThat(columnType("documents", "context_prefix_stamp")).isNull();
    UUID libraryId = insertLibrary();
    UUID documentId = insertDocument(libraryId);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(columnType("documents", "context_prefix_stamp")).isEqualTo("character varying");
    assertThat(columnType("documents", "context_prefix_eligible")).isEqualTo("boolean");
    assertThat(columnType("knowledge_libraries", "core_context_prefix_document_type"))
        .isEqualTo("boolean");
    assertThat(columnType("knowledge_libraries", "core_context_prefix_document_date"))
        .isEqualTo("boolean");

    assertThat(coreFlag(libraryId, "core_context_prefix_document_type")).isFalse();
    assertThat(coreFlag(libraryId, "core_context_prefix_document_date")).isFalse();
    assertThat(stampOf(documentId))
        .as("the Altbestand was never embedded under a recorded prefix and is therefore pending")
        .isNull();
    assertThat(partialIndexExists())
        .as("the run's selection reads a partial index over exactly the pending rows")
        .isTrue();
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

  private boolean partialIndexExists() throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT indexdef FROM pg_indexes WHERE indexname ="
                + " 'idx_documents_library_context_prefix'")) {
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next() && rs.getString(1).contains("context_prefix_stamp IS NULL");
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

  private String stampOf(UUID documentId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT context_prefix_stamp FROM documents WHERE id = ?")) {
      statement.setObject(1, documentId);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getString(1);
      }
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
