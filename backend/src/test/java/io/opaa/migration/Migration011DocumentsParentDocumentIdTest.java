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
 * cascading delete - plus the backfill that seeds it from the existing RSS {@code
 * source_entry_url}/{@code file_path} convention, scoped to {@code library_id} since {@code
 * file_path} is only unique within a library ({@code uk_documents_library_path}).
 *
 * <p>Backfill tests insert their fixture rows on the pre-migration schema (no {@code
 * parent_document_id} column yet) and only then apply the changeSet, so the backfill under test
 * actually runs against them - the FK/nullability tests do the reverse, applying the changeSet
 * first since they need the column and constraint to already exist.
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
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void parentDocumentIdIsNullableAndDefaultsToNull() throws Exception {
    UUID documentId = insertDocumentPreMigration(libraryId, "/corpus/report.pdf", null);
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(parentDocumentId(documentId)).isNull();
  }

  @Test
  void parentDocumentIdAcceptsAnExistingDocumentAsParent() throws Exception {
    UUID parentId = insertDocumentPreMigration(libraryId, "https://feed.example/entry", null);
    applyChangelog(connection, CHANGELOG_PATH);
    UUID childId =
        insertDocumentPostMigration(libraryId, "/attachments/report.pdf", null, parentId);

    assertThat(parentDocumentId(childId)).isEqualTo(parentId);
  }

  @Test
  void parentDocumentIdRejectsAnIdThatIsNotAnExistingDocument() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID danglingParentId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                insertDocumentPostMigration(
                    libraryId, "/attachments/report.pdf", null, danglingParentId))
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
    applyChangelog(connection, CHANGELOG_PATH);
    UUID parentId =
        insertDocumentPostMigration(libraryId, "https://feed.example/entry", null, null);
    insertDocumentPostMigration(libraryId, "/attachments/report.pdf", null, parentId);

    assertThatThrownBy(() -> deleteDocument(parentId))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_documents_parent");
  }

  @Test
  void backfillLinksAnRssAttachmentToItsEntryByFilePathAndLibrary() throws Exception {
    UUID entryId = insertDocumentPreMigration(libraryId, "https://feed.example/entry-1", null);
    UUID attachmentId =
        insertDocumentPreMigration(
            libraryId, "/attachments/report.pdf", "https://feed.example/entry-1");

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(parentDocumentId(attachmentId)).isEqualTo(entryId);
  }

  /**
   * The library-scoping requirement the coordinator called out: the same entry URL indexed into two
   * different libraries produces two independent entry rows (per {@code
   * uk_documents_library_path}), and the backfill must not let an attachment in one library link to
   * an identically-pathed entry that belongs to another.
   */
  @Test
  void backfillDoesNotCrossLibraryBoundaries() throws Exception {
    UUID otherOwnerId = insertUser("other-owner");
    UUID otherLibraryId = insertLibrary(otherOwnerId);
    insertDocumentPreMigration(otherLibraryId, "https://feed.example/entry-1", null);
    UUID entryInOwnLibrary =
        insertDocumentPreMigration(libraryId, "https://feed.example/entry-1", null);
    UUID attachmentId =
        insertDocumentPreMigration(
            libraryId, "/attachments/report.pdf", "https://feed.example/entry-1");

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(parentDocumentId(attachmentId)).isEqualTo(entryInOwnLibrary);
  }

  @Test
  void backfillLeavesADocumentWithoutASourceEntryUrlUntouched() throws Exception {
    UUID documentId = insertDocumentPreMigration(libraryId, "/corpus/report.pdf", null);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(parentDocumentId(documentId)).isNull();
  }

  /**
   * A dangling {@code source_entry_url} (the referenced entry row does not exist, e.g. it was
   * deleted before this migration ran) must not turn the backfill's own {@code UPDATE ... FROM}
   * into a constraint violation - it simply finds no matching parent row and leaves the column
   * {@code null}, exactly like {@link #backfillLeavesADocumentWithoutASourceEntryUrlUntouched}.
   */
  @Test
  void backfillLeavesADanglingSourceEntryUrlAsNull() throws Exception {
    UUID attachmentId =
        insertDocumentPreMigration(
            libraryId, "/attachments/report.pdf", "https://feed.example/vanished-entry");

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(parentDocumentId(attachmentId)).isNull();
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

  /** Inserts a document on the schema as it exists before this migration's changeSet runs. */
  private UUID insertDocumentPreMigration(UUID libraryId, String filePath, String sourceEntryUrl)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO documents (id, file_name, file_path, status, source_type, library_id,"
                + " organization_id, source_entry_url) VALUES (?, 'report.pdf', ?, 'INDEXED',"
                + " 'RSS_FEED', ?, ?, ?)")) {
      statement.setObject(1, id);
      statement.setString(2, filePath);
      statement.setObject(3, libraryId);
      statement.setObject(4, ORGANIZATION_ID);
      statement.setString(5, sourceEntryUrl);
      statement.executeUpdate();
    }
    return id;
  }

  /** Inserts a document once this migration's changeSet has already run. */
  private UUID insertDocumentPostMigration(
      UUID libraryId, String filePath, String sourceEntryUrl, UUID parentDocumentId)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO documents (id, file_name, file_path, status, source_type, library_id,"
                + " organization_id, source_entry_url, parent_document_id) VALUES (?, 'report.pdf',"
                + " ?, 'INDEXED', 'RSS_FEED', ?, ?, ?, ?)")) {
      statement.setObject(1, id);
      statement.setString(2, filePath);
      statement.setObject(3, libraryId);
      statement.setObject(4, ORGANIZATION_ID);
      statement.setString(5, sourceEntryUrl);
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
