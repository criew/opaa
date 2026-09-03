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
 * Delta test for {@code changes/011-documents-parent-document-id.yaml} (ADR-0022, Entscheidung 4,
 * #1180): pins all three properties the changeSet's comment promises for {@code
 * documents.parent_document_id} - nullable, FK-enforced against {@code documents(id)}, and no
 * cascading delete. Deliberately schema-only, no backfill: the changeSet's own comment explains why
 * a backfill against Bestandsdaten belongs in #1182 instead, alongside the delete-path fixes
 * (processRssEntry, the single-document delete API, PipelineReindexService#advance) it depends on.
 */
class Migration011DocumentsParentDocumentIdTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/011-documents-parent-document-id.yaml";
  private static final UUID ORGANIZATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  private Connection connection;
  private UUID libraryId;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    UUID ownerUserId = insertUser("owner");
    libraryId = insertLibrary(ownerUserId);
    applyChangelog(connection, CHANGELOG_PATH);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void parentDocumentIdIsNullableAndDefaultsToNull() throws Exception {
    UUID documentId = insertDocument(libraryId, "/corpus/report.pdf", null);

    assertThat(parentDocumentId(documentId)).isNull();
  }

  @Test
  void parentDocumentIdAcceptsAnExistingDocumentAsParent() throws Exception {
    UUID parentId = insertDocument(libraryId, "https://feed.example/entry", null);
    UUID childId = insertDocument(libraryId, "/attachments/report.pdf", parentId);

    assertThat(parentDocumentId(childId)).isEqualTo(parentId);
  }

  @Test
  void parentDocumentIdRejectsAnIdThatIsNotAnExistingDocument() {
    UUID danglingParentId = UUID.randomUUID();

    assertThatThrownBy(() -> insertDocument(libraryId, "/attachments/report.pdf", danglingParentId))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_documents_parent");
  }

  /**
   * The property the changeSet's comment names as the entire reason for omitting {@code ON DELETE
   * CASCADE}: deleting a parent while a child still references it must fail at the FK, not silently
   * orphan the child's pgvector chunks.
   */
  @Test
  void deletingAParentWithAnExistingChildFailsInsteadOfCascading() throws Exception {
    UUID parentId = insertDocument(libraryId, "https://feed.example/entry", null);
    insertDocument(libraryId, "/attachments/report.pdf", parentId);

    assertThatThrownBy(() -> deleteDocument(parentId))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_documents_parent");
  }

  private UUID parentDocumentId(UUID documentId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT parent_document_id FROM documents WHERE id = ?")) {
      statement.setObject(1, documentId);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).as("document %s must exist", documentId).isTrue();
        return rs.getObject("parent_document_id", UUID.class);
      }
    }
  }

  private void deleteDocument(UUID documentId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("DELETE FROM documents WHERE id = ?")) {
      statement.setObject(1, documentId);
      statement.executeUpdate();
    }
  }

  private UUID insertDocument(UUID libraryId, String filePath, UUID parentDocumentId)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO documents (id, file_name, file_path, status, source_type, library_id,"
                + " organization_id, parent_document_id) VALUES (?, 'report.pdf', ?, 'INDEXED',"
                + " 'RSS_FEED', ?, ?, ?)")) {
      statement.setObject(1, id);
      statement.setString(2, filePath);
      statement.setObject(3, libraryId);
      statement.setObject(4, ORGANIZATION_ID);
      statement.setObject(5, parentDocumentId);
      statement.executeUpdate();
    }
    return id;
  }

  private UUID insertUser(String subject) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO users (id, subject, issuer, organization_id) VALUES (?, ?, 'test', ?)")) {
      statement.setObject(1, id);
      statement.setString(2, subject + "-" + id);
      statement.setObject(3, ORGANIZATION_ID);
      statement.executeUpdate();
    }
    return id;
  }

  private UUID insertLibrary(UUID ownerUserId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type,"
                + " owner_user_id, visibility, source_type)"
                + " VALUES (?, ?, ?, 'USER', ?, 'PRIVATE', 'UPLOAD')")) {
      statement.setObject(1, id);
      statement.setObject(2, ORGANIZATION_ID);
      statement.setString(3, "Bibliothek " + id);
      statement.setObject(4, ownerUserId);
      statement.executeUpdate();
    }
    return id;
  }
}
