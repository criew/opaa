package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta test for {@code changes/017-documents-library-checksum-parentless.yaml} (#1218): the upload
 * dedup index {@code uk_documents_library_checksum} no longer covers attachment rows ({@code
 * parent_document_id} set), so two mails in the same UPLOAD library may carry an identical
 * attachment (same checksum) - while the dedup guarantee for parentless (user-uploaded) rows stays
 * exactly as before.
 */
class Migration017DocumentsLibraryChecksumParentlessTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/017-documents-library-checksum-parentless.yaml";
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
    // 017 rewrites an index predicate over 011's column - applied inline, no shared fixture file
    // needed yet (AbstractMigrationTest's own Javadoc).
    applyChangelog(connection, "db/changelog/changes/011-documents-parent-document-id.yaml");
    applyChangelog(connection, CHANGELOG_PATH);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void twoAttachmentRowsWithTheSameChecksumInTheSameLibraryAreAllowed() throws Exception {
    UUID firstMail = insertDocument(libraryId, "/uploads/a.eml", "mail-a", null);
    UUID secondMail = insertDocument(libraryId, "/uploads/b.eml", "mail-b", null);

    insertDocument(libraryId, "/uploads/a.eml/0/anlage.pdf", "same-attachment", firstMail);
    insertDocument(libraryId, "/uploads/b.eml/0/anlage.pdf", "same-attachment", secondMail);
  }

  @Test
  void anAttachmentRowMayShareItsChecksumWithAParentlessUpload() throws Exception {
    UUID mail = insertDocument(libraryId, "/uploads/a.eml", "mail-a", null);
    insertDocument(libraryId, "/uploads/plain.pdf", "shared-bytes", null);

    insertDocument(libraryId, "/uploads/a.eml/0/anlage.pdf", "shared-bytes", mail);
  }

  @Test
  void twoParentlessUploadsWithTheSameChecksumInTheSameLibraryStillCollide() throws Exception {
    insertDocument(libraryId, "/uploads/one.pdf", "duplicate", null);

    assertThatThrownBy(() -> insertDocument(libraryId, "/uploads/two.pdf", "duplicate", null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_documents_library_checksum");
  }

  @Test
  void theSameChecksumInADifferentLibraryIsStillAllowed() throws Exception {
    UUID otherLibraryId = insertLibrary(insertUser("other-owner"));
    insertDocument(libraryId, "/uploads/one.pdf", "cross-library", null);

    UUID inserted = insertDocument(otherLibraryId, "/uploads/one.pdf", "cross-library", null);
    assertThat(inserted).isNotNull();
  }

  private UUID insertDocument(
      UUID libraryId, String filePath, String checksum, UUID parentDocumentId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO documents (id, file_name, file_path, status, source_type, library_id,"
                + " organization_id, checksum, parent_document_id) VALUES (?, 'datei.pdf', ?,"
                + " 'INDEXED', 'UPLOAD', ?, ?, ?, ?)")) {
      statement.setObject(1, id);
      statement.setString(2, filePath);
      statement.setObject(3, libraryId);
      statement.setObject(4, ORGANIZATION_ID);
      statement.setString(5, checksum);
      statement.setObject(6, parentDocumentId);
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
