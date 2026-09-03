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
 * Delta test for {@code changes/016-documents-parent-document-id-backfill.yaml} (ADR-0022,
 * Entscheidung 4, #1182): backfills {@code parent_document_id} for existing RSS attachment rows by
 * joining {@code source_entry_url} against the parent's own {@code file_path} within the same
 * library - the same identity {@code RssFeedIndexingExecutor#isUnchanged} and {@code
 * DocumentRepository#existsBySourceEntryUrlAndLibraryId} already use.
 */
class Migration016DocumentsParentDocumentIdBackfillTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/016-documents-parent-document-id-backfill.yaml";
  private static final UUID ORGANIZATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  private Connection connection;
  private UUID libraryId;
  private UUID otherLibraryId;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    UUID ownerUserId = insertUser("owner");
    libraryId = insertLibrary(ownerUserId);
    otherLibraryId = insertLibrary(ownerUserId);
    // 016 depends on 011's column, added schema-only - applied here rather than via a dedicated
    // fixture file, since 016 is (so far) the only changeSet that needs it (AbstractMigrationTest's
    // own Javadoc: a fixture file is only warranted once several changeSets share it).
    applyChangelog(connection, "db/changelog/changes/011-documents-parent-document-id.yaml");
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void linksAnAttachmentToItsEntryByMatchingSourceEntryUrlAndFilePath() throws Exception {
    UUID entryId = insertDocument(libraryId, "https://feed.example/entry", null);
    UUID attachmentId =
        insertDocument(
            libraryId, "https://feed.example/attachment.pdf", "https://feed.example/entry");

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(parentDocumentId(attachmentId)).isEqualTo(entryId);
    assertThat(parentDocumentId(entryId)).isNull();
  }

  @Test
  void neverLinksACrossLibraryMatch() throws Exception {
    insertDocument(otherLibraryId, "https://feed.example/entry", null);
    UUID attachmentId =
        insertDocument(
            libraryId, "https://feed.example/attachment.pdf", "https://feed.example/entry");

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(parentDocumentId(attachmentId)).isNull();
  }

  /**
   * The self-reference guard the #1188 review flagged: {@code AND child.id <> parent.id} must stop
   * a row whose {@code source_entry_url} happens to equal its own {@code file_path} from becoming
   * its own parent - theoretical for today's RSS identity scheme, but a backfill that runs once
   * against Bestandsdaten must not depend on that holding by construction.
   */
  @Test
  void neverLinksARowToItselfEvenWhenSourceEntryUrlEqualsItsOwnFilePath() throws Exception {
    UUID selfReferencingId =
        insertDocument(libraryId, "https://feed.example/self", "https://feed.example/self");

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(parentDocumentId(selfReferencingId)).isNull();
  }

  @Test
  void isIdempotentWhenRunTwice() throws Exception {
    UUID entryId = insertDocument(libraryId, "https://feed.example/entry", null);
    UUID attachmentId =
        insertDocument(
            libraryId, "https://feed.example/attachment.pdf", "https://feed.example/entry");

    applyChangelog(connection, CHANGELOG_PATH);
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(parentDocumentId(attachmentId)).isEqualTo(entryId);
  }

  /**
   * {@code WHERE parent_document_id IS NULL} in the changeSet's own {@code UPDATE} must never
   * overwrite a link a caller already set explicitly (e.g. by #1182's own RSS writers, once
   * deployed) with a different, backfill-derived value.
   */
  @Test
  void neverOverwritesAnAlreadySetParentDocumentId() throws Exception {
    UUID entryId = insertDocument(libraryId, "https://feed.example/entry", null);
    UUID otherParentId = insertDocument(libraryId, "https://feed.example/other-entry", null);
    UUID attachmentId =
        insertDocument(
            libraryId, "https://feed.example/attachment.pdf", "https://feed.example/entry");
    setParentDocumentId(attachmentId, otherParentId);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(parentDocumentId(attachmentId)).isEqualTo(otherParentId);
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

  private void setParentDocumentId(UUID documentId, UUID parentDocumentId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("UPDATE documents SET parent_document_id = ? WHERE id = ?")) {
      statement.setObject(1, parentDocumentId);
      statement.setObject(2, documentId);
      statement.executeUpdate();
    }
  }

  private UUID insertDocument(UUID libraryId, String filePath, String sourceEntryUrl)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO documents (id, file_name, file_path, source_entry_url, status,"
                + " source_type, library_id, organization_id) VALUES (?, 'report.pdf', ?, ?,"
                + " 'INDEXED', 'RSS_FEED', ?, ?)")) {
      statement.setObject(1, id);
      statement.setString(2, filePath);
      statement.setString(3, sourceEntryUrl);
      statement.setObject(4, libraryId);
      statement.setObject(5, ORGANIZATION_ID);
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
