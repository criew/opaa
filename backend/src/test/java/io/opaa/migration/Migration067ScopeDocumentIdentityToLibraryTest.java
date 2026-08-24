package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Applies Liquibase changelog 067 in isolation against a database built from the real, versioned
 * changelog through changeSet 050 (the same {@code test-master-through-050.yaml} fixture {@code
 * Migration051CreateSpaceAssetAssociationsTest}/{@code Migration052CreateNotificationsTest} use) -
 * {@code documents.library_id} has existed and been {@code NOT NULL} since changeSet 012, well
 * within that fixture.
 *
 * <p>Covers what {@code DocumentIndexingIntegrationTest} demonstrates at the service layer from the
 * database side: that {@code uk_documents_library_path} actually rejects a second {@code
 * (library_id, file_path)} pair at the database level (#877, Epic #826 Befund B6), while still
 * allowing the same {@code file_path} to coexist across two different libraries - the exact case
 * the pre-067 global {@code findByFilePath} lookup used to "steal" a document over. Also covers the
 * self-healing cleanup changeset (#877 review) that runs ahead of the constraint.
 */
class Migration067ScopeDocumentIdentityToLibraryTest extends AbstractMigrationTest {

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-050.yaml";
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
  void rejectsASecondDocumentWithTheSamePathInTheSameLibrary() throws Exception {
    UUID libraryId = insertLibrary(insertUser());
    applyChangelog067();

    insertDocument(libraryId, "/corpus/report.pdf");

    assertThatThrownBy(() -> insertDocument(libraryId, "/corpus/report.pdf"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_documents_library_path");
  }

  @Test
  void allowsTheSamePathInTwoDifferentLibraries() throws Exception {
    // The exact case #877 fixes: two libraries indexing the same source (URL or path) must yield
    // two independent documents, not one library "stealing" the other's document.
    UUID firstLibrary = insertLibrary(insertUser());
    UUID secondLibrary = insertLibrary(insertUser());
    applyChangelog067();

    UUID firstDoc = insertDocument(firstLibrary, "https://example.com/report.pdf");
    UUID secondDoc = insertDocument(secondLibrary, "https://example.com/report.pdf");

    assertThat(documentExists(firstDoc)).isTrue();
    assertThat(documentExists(secondDoc)).isTrue();
    assertThat(countDocuments()).isEqualTo(2);
  }

  @Test
  void deduplicatesPreExistingDuplicatesKeepingTheMostRecentRowBeforeAddingTheConstraint()
      throws Exception {
    // #877 review, finding 3: the self-healing cleanup changeset ahead of the constraint - a
    // defensive backstop, not evidence the "no duplicates can exist" analysis is in doubt (see
    // that changeSet's own comment). Bypasses the (not yet applied) constraint by inserting
    // directly, exactly as a hypothetical pre-067 duplicate would have accumulated.
    UUID libraryId = insertLibrary(insertUser());
    UUID older = insertDocument(libraryId, "/corpus/report.pdf", "2020-01-01T00:00:00Z");
    UUID newer = insertDocument(libraryId, "/corpus/report.pdf", "2024-06-01T00:00:00Z");

    applyChangelog067();

    assertThat(documentExists(older)).as("the older duplicate is removed").isFalse();
    assertThat(documentExists(newer)).as("the most recently created row is kept").isTrue();
    assertThat(countDocuments()).isEqualTo(1);
    // The constraint itself must have been created successfully afterwards - the whole point of
    // running the cleanup first.
    assertThat(hasUniqueConstraint()).isTrue();
  }

  @Test
  void leavesDistinctPathsAndLibrariesUntouchedByTheCleanup() throws Exception {
    UUID libraryId = insertLibrary(insertUser());
    UUID otherLibraryId = insertLibrary(insertUser());
    UUID distinctPath = insertDocument(libraryId, "/corpus/other.pdf");
    UUID sameSourceDifferentLibrary = insertDocument(otherLibraryId, "/corpus/report.pdf");
    UUID onlyCopy = insertDocument(libraryId, "/corpus/report.pdf");

    applyChangelog067();

    assertThat(documentExists(distinctPath)).isTrue();
    assertThat(documentExists(sameSourceDifferentLibrary)).isTrue();
    assertThat(documentExists(onlyCopy)).isTrue();
    assertThat(countDocuments()).isEqualTo(3);
  }

  @Test
  void rollbackDropsTheConstraint() throws Exception {
    applyChangelog067();
    assertThat(hasUniqueConstraint()).isTrue();

    Liquibase liquibase =
        new Liquibase(
            "db/changelog/changes/067-scope-document-identity-to-library.yaml",
            new ClassLoaderResourceAccessor(),
            liquibaseDatabase(connection));
    liquibase.rollback(1, (String) null);
    connection.setAutoCommit(true);

    assertThat(hasUniqueConstraint()).isFalse();
  }

  private void applyChangelog067() throws Exception {
    applyChangelog(connection, "db/changelog/changes/067-scope-document-identity-to-library.yaml");
  }

  private UUID insertUser() throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
              + " organization_id) VALUES ('"
              + id
              + "', '"
              + id
              + "', 'test-issuer', '"
              + id
              + "@example.com', 'User', now(), 'USER', '"
              + SEEDED_ORGANIZATION_ID
              + "')");
    }
    return id;
  }

  private UUID insertLibrary(UUID ownerId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO knowledge_libraries "
              + "(id, organization_id, name, owner_type, owner_user_id, owner_group_id,"
              + " visibility, listed, source_type, created_at, updated_at) VALUES ('"
              + id
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', 'Bibliothek "
              + id
              + "', 'USER', '"
              + ownerId
              + "', NULL, 'PRIVATE', false, 'UPLOAD', now(), now())");
    }
    return id;
  }

  private UUID insertDocument(UUID libraryId, String filePath) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO documents (id, file_name, file_path, status, source_type, library_id,"
              + " organization_id) VALUES ('"
              + id
              + "', 'report.pdf', '"
              + filePath
              + "', 'INDEXED', 'HTTP_DIRECTORY', '"
              + libraryId
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "')");
    }
    return id;
  }

  /** Same as {@link #insertDocument(UUID, String)}, with an explicit {@code created_at}. */
  private UUID insertDocument(UUID libraryId, String filePath, String createdAt)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO documents (id, file_name, file_path, status, source_type, library_id,"
              + " organization_id, created_at) VALUES ('"
              + id
              + "', 'report.pdf', '"
              + filePath
              + "', 'INDEXED', 'HTTP_DIRECTORY', '"
              + libraryId
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', '"
              + createdAt
              + "')");
    }
    return id;
  }

  private boolean documentExists(UUID documentId) throws SQLException {
    return count("SELECT count(*) FROM documents WHERE id = '" + documentId + "'") == 1;
  }

  private int countDocuments() throws SQLException {
    return count("SELECT count(*) FROM documents");
  }

  private boolean hasUniqueConstraint() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM pg_constraint WHERE conname ="
                    + " 'uk_documents_library_path'")) {
      result.next();
      return result.getInt(1) == 1;
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
