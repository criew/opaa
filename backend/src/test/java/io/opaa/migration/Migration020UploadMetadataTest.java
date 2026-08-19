package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Applies Liquibase changelog 020 in isolation against a database built from the real, versioned
 * changelog through changeSet 016 - the same pattern as {@code
 * Migration016VectorStoreLibraryMetadataTest}, with {@code test-master-through-016.yaml} as the
 * pre-migration fixture. 016, not 017-019, because 017/018/019 are reserved by parallel work
 * (#427/#428/#431) not yet on {@code main} when this changeSet's number was chosen - see this
 * changeSet's own comment in {@code 020-add-upload-metadata-to-documents.yaml} and {@code
 * db.changelog-master.yaml}, which merging those three PRs will need to reconcile as a simple
 * `include` reordering, not a schema conflict.
 *
 * <p>Covers the claims the changeSet's comments make that {@code
 * LibraryDocumentServiceIntegrationTest} cannot: that {@code chk_documents_source_type}'s widening
 * applies to a table that already has {@code FILESYSTEM}/{@code HTTP_DIRECTORY} rows (that test
 * starts from an empty, already-migrated schema and only ever inserts through the application,
 * which never violates the check either way), that {@code fk_documents_uploaded_by_user}'s {@code
 * ON DELETE SET NULL} actually detaches an uploader from their documents instead of blocking the
 * user delete - the opposite of {@code fk_knowledge_libraries_owner_user}'s {@code RESTRICT} - and
 * that {@code uk_documents_library_checksum}'s two conditions are both load-bearing (#420 second
 * code review round, nit 3): it must reject a second {@code UPLOAD} row with the same {@code
 * (library_id, checksum)}, but two {@code FILESYSTEM} rows sharing a checksum in the same library
 * (two differently-named crawled files with identical content, never deduplicated by checksum - see
 * {@code FileProcessingService#processFile}) must keep coexisting, exactly as the index's own
 * {@code source_type = 'UPLOAD'} condition intends.
 */
@Testcontainers(disabledWithoutDocker = true)
class Migration020UploadMetadataTest extends AbstractMigrationTest {

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-016.yaml";
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
  void widenedCheckConstraintAcceptsUploadOnATableThatAlreadyHasLegacyRows() throws Exception {
    // A pre-existing FILESYSTEM row, written under the pre-020 constraint - the exact situation
    // "widen a CHECK on a table with existing data" needs to be proven against, not assumed.
    insertDocument("legacy.txt", "FILESYSTEM", null, null);

    applyChangelog020();

    // The migration itself must not have failed re-validating the existing row, and the widened
    // constraint must now accept the new value.
    UUID uploadDoc = insertDocument("upload.pdf", "UPLOAD", null, null);
    assertThat(sourceType(uploadDoc)).isEqualTo("UPLOAD");
    assertThat(documentCount()).isEqualTo(2);
  }

  @Test
  void uniqueChecksumIndexRejectsASecondUploadWithTheSameChecksumInTheSameLibrary()
      throws Exception {
    applyChangelog020();
    insertDocument("first.pdf", "UPLOAD", null, "same-checksum");

    assertThatThrownBy(() -> insertDocument("second.pdf", "UPLOAD", null, "same-checksum"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_documents_library_checksum");
  }

  @Test
  void uniqueChecksumIndexLeavesFilesystemDuplicatesInTheSameLibraryCoexisting() throws Exception {
    // The index's second condition (source_type = 'UPLOAD') exists precisely for this case: two
    // differently-named crawled files with identical content are today two legitimate, independent
    // FILESYSTEM rows in the same library (processFile dedups by file_path, not checksum) - a
    // library-wide index without this condition would turn that pre-existing, harmless case into a
    // failed indexing run the moment it occurred.
    applyChangelog020();
    insertDocument("report-copy-1.txt", "FILESYSTEM", null, "identical-content-checksum");

    UUID second =
        insertDocument("report-copy-2.txt", "FILESYSTEM", null, "identical-content-checksum");

    assertThat(sourceType(second)).isEqualTo("FILESYSTEM");
    assertThat(documentCount()).isEqualTo(2);
  }

  @Test
  void aValueOutsideTheWidenedSetIsStillRejected() throws Exception {
    applyChangelog020();

    assertThatThrownBy(() -> insertDocument("bogus.txt", "BOGUS", null, null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_documents_source_type");
  }

  @Test
  void deletingTheUploadingUserDetachesTheDocumentInsteadOfBeingBlocked() throws Exception {
    applyChangelog020();
    UUID uploader = insertUser("uploader-subject");
    UUID document = insertDocument("upload.pdf", "UPLOAD", uploader, null);
    assertThat(uploadedByUserId(document)).isEqualTo(uploader.toString());

    // ON DELETE SET NULL, not RESTRICT (unlike fk_knowledge_libraries_owner_user): removing the
    // uploading user's account must not be blocked by, and must not cascade-delete, documents they
    // uploaded.
    deleteUser(uploader);

    assertThat(documentExists(document)).isTrue();
    assertThat(uploadedByUserId(document)).isNull();
  }

  private void applyChangelog020() throws Exception {
    applyChangelog(connection, "db/changelog/changes/020-add-upload-metadata-to-documents.yaml");
  }

  private UUID insertUser(String subject) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO users (id, subject, issuer, email, display_name, created_at,"
              + " organization_id) VALUES ('"
              + id
              + "', '"
              + subject
              + "', 'issuer', 'user@example.com', 'User', now(), '"
              + SEEDED_ORGANIZATION_ID
              + "')");
    }
    return id;
  }

  private void deleteUser(UUID userId) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM users WHERE id = '" + userId + "'");
    }
  }

  private UUID insertDocument(
      String fileName, String sourceType, UUID uploadedByUserId, String checksum)
      throws SQLException {
    UUID id = UUID.randomUUID();
    String uploadedByColumn = uploadedByUserId == null ? "" : ", uploaded_by_user_id";
    String uploadedByValue = uploadedByUserId == null ? "" : ", '" + uploadedByUserId + "'";
    String checksumColumn = checksum == null ? "" : ", checksum";
    String checksumValue = checksum == null ? "" : ", '" + checksum + "'";
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO documents (id, file_name, file_path, status, source_type, library_id,"
              + " organization_id"
              + uploadedByColumn
              + checksumColumn
              + ") VALUES ('"
              + id
              + "', '"
              + fileName
              + "', '/corpus/"
              + fileName
              + "', 'INDEXED', '"
              + sourceType
              + "', '00000000-0000-0000-0000-000000000002', '"
              + SEEDED_ORGANIZATION_ID
              + "'"
              + uploadedByValue
              + checksumValue
              + ")");
    }
    return id;
  }

  private String sourceType(UUID documentId) throws SQLException {
    return stringColumn("source_type", documentId);
  }

  private String uploadedByUserId(UUID documentId) throws SQLException {
    return stringColumn("uploaded_by_user_id", documentId);
  }

  private boolean documentExists(UUID documentId) throws SQLException {
    return count("SELECT count(*) FROM documents WHERE id = '" + documentId + "'") == 1;
  }

  private int documentCount() throws SQLException {
    return count("SELECT count(*) FROM documents");
  }

  private String stringColumn(String column, UUID documentId) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT " + column + " FROM documents WHERE id = '" + documentId + "'")) {
      return result.next() ? result.getString(1) : null;
    }
  }

  private int count(String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      result.next();
      return result.getInt(1);
    }
  }
}
