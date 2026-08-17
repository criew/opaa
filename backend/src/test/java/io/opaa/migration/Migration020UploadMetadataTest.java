package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

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
 * <p>Covers the two claims the changeSet's comments make that {@code
 * LibraryDocumentServiceIntegrationTest} cannot: that {@code chk_documents_source_type}'s widening
 * applies to a table that already has {@code FILESYSTEM}/{@code HTTP_DIRECTORY} rows (that test
 * starts from an empty, already-migrated schema and only ever inserts through the application,
 * which never violates the check either way), and that {@code fk_documents_uploaded_by_user}'s
 * {@code ON DELETE SET NULL} actually detaches an uploader from their documents instead of blocking
 * the user delete - the opposite of {@code fk_knowledge_libraries_owner_user}'s {@code RESTRICT}.
 */
@Testcontainers(disabledWithoutDocker = true)
class Migration020UploadMetadataTest {

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"));

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";

  private Connection connection;
  private Database database;

  @BeforeEach
  void setUp() throws Exception {
    connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    database =
        DatabaseFactory.getInstance()
            .findCorrectDatabaseImplementation(new JdbcConnection(connection));

    Liquibase liquibase =
        new Liquibase(
            "db/changelog/test-master-through-016.yaml",
            new ClassLoaderResourceAccessor(),
            database);
    liquibase.update(new Contexts());
    connection.setAutoCommit(true);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.setAutoCommit(true);
    try (Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA public CASCADE");
      statement.execute("CREATE SCHEMA public");
    }
    connection.close();
  }

  @Test
  void widenedCheckConstraintAcceptsUploadOnATableThatAlreadyHasLegacyRows() throws Exception {
    // A pre-existing FILESYSTEM row, written under the pre-020 constraint - the exact situation
    // "widen a CHECK on a table with existing data" needs to be proven against, not assumed.
    insertDocument("legacy.txt", "FILESYSTEM", null);

    applyChangelog020();

    // The migration itself must not have failed re-validating the existing row, and the widened
    // constraint must now accept the new value.
    UUID uploadDoc = insertDocument("upload.pdf", "UPLOAD", null);
    assertThat(sourceType(uploadDoc)).isEqualTo("UPLOAD");
    assertThat(documentCount()).isEqualTo(2);
  }

  @Test
  void aValueOutsideTheWidenedSetIsStillRejected() throws Exception {
    applyChangelog020();

    assertThatThrownBy(() -> insertDocument("bogus.txt", "BOGUS", null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_documents_source_type");
  }

  @Test
  void deletingTheUploadingUserDetachesTheDocumentInsteadOfBeingBlocked() throws Exception {
    applyChangelog020();
    UUID uploader = insertUser("uploader-subject");
    UUID document = insertDocument("upload.pdf", "UPLOAD", uploader);
    assertThat(uploadedByUserId(document)).isEqualTo(uploader.toString());

    // ON DELETE SET NULL, not RESTRICT (unlike fk_knowledge_libraries_owner_user): removing the
    // uploading user's account must not be blocked by, and must not cascade-delete, documents they
    // uploaded.
    deleteUser(uploader);

    assertThat(documentExists(document)).isTrue();
    assertThat(uploadedByUserId(document)).isNull();
  }

  private void applyChangelog020() throws Exception {
    Liquibase liquibase =
        new Liquibase(
            "db/changelog/changes/020-add-upload-metadata-to-documents.yaml",
            new ClassLoaderResourceAccessor(),
            database);
    liquibase.update(new Contexts());
    connection.setAutoCommit(true);
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

  private UUID insertDocument(String fileName, String sourceType, UUID uploadedByUserId)
      throws SQLException {
    UUID id = UUID.randomUUID();
    String uploadedByColumn = uploadedByUserId == null ? "" : ", uploaded_by_user_id";
    String uploadedByValue = uploadedByUserId == null ? "" : ", '" + uploadedByUserId + "'";
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO documents (id, file_name, file_path, status, source_type, library_id,"
              + " organization_id"
              + uploadedByColumn
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
