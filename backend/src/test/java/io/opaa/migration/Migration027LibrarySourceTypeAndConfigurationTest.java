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
 * Applies Liquibase changelog 024 in isolation against a database built from the real, versioned
 * changelog through changeSet 016 - the same pattern {@code Migration020UploadMetadataTest} and
 * {@code Migration023AuditRetentionTest} establish (see their own Javadoc): the bootstrap fixture
 * deliberately stops at 016, not 023, because 024 (like 020) touches none of the audit-log
 * infrastructure 017-023 introduce, and applying 017's ownership transfer of {@code audit_log} to
 * {@code opaa_audit_owner} under a plain superuser bootstrap connection - rather than the
 * restricted {@code AUDIT_APP_ROLE} those tests use - is neither needed here nor safe to skip
 * halfway (022's {@code SET ROLE opaa_audit_owner} step fails outside that dance).
 * knowledge_libraries, users and organizations all already exist by changeSet 012, well within the
 * 016 fixture.
 *
 * <p>Covers the claims 024's changeSet comments make that {@code
 * KnowledgeLibraryServiceIntegrationTest} cannot: that the backfill actually widens a table with
 * pre-existing rows (that test only ever inserts through the application, which never violates the
 * post-024 constraints either way), and that {@code chk_knowledge_libraries_source_configuration}
 * rejects an invalid column/type combination at the database level, not only in {@code
 * KnowledgeLibraryService}.
 */
@Testcontainers(disabledWithoutDocker = true)
class Migration024LibrarySourceTypeAndConfigurationTest {

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"));

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";
  private static final String SYSTEM_LIBRARY_ID = "00000000-0000-0000-0000-000000000002";

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
  void backfillAssignsUploadToEveryPreExistingLibraryIncludingTheSeededSystemLibrary()
      throws Exception {
    // A pre-024 library, written before source_type existed at all - the seeded system library
    // (012-seed-system-library) is exactly this case, still present from test-master-through-023.
    UUID ownedLibrary = insertLibraryWithoutSourceType("USER");

    applyChangelog024();

    assertThat(sourceType(ownedLibrary)).isEqualTo("UPLOAD");
    assertThat(sourceType(UUID.fromString(SYSTEM_LIBRARY_ID))).isEqualTo("UPLOAD");
  }

  @Test
  void enforcedNotNullAndCheckConstraintRejectAnUnknownSourceTypeAfterMigration() throws Exception {
    applyChangelog024();

    // An unknown source_type violates both chk_knowledge_libraries_source_type (not one of the
    // known values) and chk_knowledge_libraries_source_configuration (matches none of its
    // OR-branches, all of which are keyed on a known value) at once - Postgres does not guarantee
    // which of two simultaneously violated CHECK constraints is reported, so this only pins that
    // the insert is rejected by one of the two, not which.
    assertThatThrownBy(() -> insertLibraryWithSourceType("USER", "CONNECTOR", null, null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_source");
  }

  @Test
  void configurationCheckAcceptsUploadWithNoConfiguration() throws Exception {
    applyChangelog024();

    UUID library = insertLibraryWithSourceType("USER", "UPLOAD", null, null);

    assertThat(sourceType(library)).isEqualTo("UPLOAD");
  }

  @Test
  void configurationCheckRejectsUploadCombinedWithAPath() throws Exception {
    applyChangelog024();

    assertThatThrownBy(() -> insertLibraryWithSourceType("USER", "UPLOAD", "/data/documents", null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
  }

  @Test
  void configurationCheckAcceptsFilesystemWithOnlyAPath() throws Exception {
    applyChangelog024();

    UUID library = insertLibraryWithSourceType("USER", "FILESYSTEM", "/data/documents", null);

    assertThat(sourceType(library)).isEqualTo("FILESYSTEM");
    assertThat(columnValue("source_path", library)).isEqualTo("/data/documents");
  }

  @Test
  void configurationCheckRejectsFilesystemWithoutAPath() throws Exception {
    applyChangelog024();

    assertThatThrownBy(() -> insertLibraryWithSourceType("USER", "FILESYSTEM", null, null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
  }

  @Test
  void configurationCheckRejectsFilesystemCombinedWithAUrl() throws Exception {
    applyChangelog024();

    assertThatThrownBy(
            () ->
                insertLibraryWithSourceType(
                    "USER", "FILESYSTEM", "/data/documents", "https://files.example.com/"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
  }

  @Test
  void configurationCheckAcceptsHttpDirectoryWithOnlyAUrl() throws Exception {
    applyChangelog024();

    UUID library =
        insertLibraryWithSourceType("USER", "HTTP_DIRECTORY", null, "https://files.example.com/");

    assertThat(sourceType(library)).isEqualTo("HTTP_DIRECTORY");
    assertThat(columnValue("source_url", library)).isEqualTo("https://files.example.com/");
  }

  @Test
  void configurationCheckRejectsHttpDirectoryWithoutAUrl() throws Exception {
    applyChangelog024();

    assertThatThrownBy(() -> insertLibraryWithSourceType("USER", "HTTP_DIRECTORY", null, null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
  }

  private void applyChangelog024() throws Exception {
    Liquibase liquibase =
        new Liquibase(
            "db/changelog/changes/024-library-source-type-and-configuration.yaml",
            new ClassLoaderResourceAccessor(),
            database);
    liquibase.update(new Contexts());
    connection.setAutoCommit(true);
  }

  private UUID insertLibraryWithoutSourceType(String ownerType) throws SQLException {
    UUID id = UUID.randomUUID();
    UUID ownerUserId = insertUser();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type,"
              + " owner_user_id, visibility, listed, personal, created_at, updated_at) VALUES ('"
              + id
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', 'Vor ADR-0018', '"
              + ownerType
              + "', '"
              + ownerUserId
              + "', 'PRIVATE', false, false, now(), now())");
    }
    return id;
  }

  private UUID insertLibraryWithSourceType(
      String ownerType, String sourceType, String sourcePath, String sourceUrl)
      throws SQLException {
    UUID id = UUID.randomUUID();
    UUID ownerUserId = insertUser();
    String pathColumn = sourcePath == null ? "" : ", source_path";
    String pathValue = sourcePath == null ? "" : ", '" + sourcePath + "'";
    String urlColumn = sourceUrl == null ? "" : ", source_url";
    String urlValue = sourceUrl == null ? "" : ", '" + sourceUrl + "'";
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type,"
              + " owner_user_id, visibility, listed, personal, source_type"
              + pathColumn
              + urlColumn
              + ", created_at, updated_at) VALUES ('"
              + id
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', 'Nach ADR-0018', '"
              + ownerType
              + "', '"
              + ownerUserId
              + "', 'PRIVATE', false, false, '"
              + sourceType
              + "'"
              + pathValue
              + urlValue
              + ", now(), now())");
    }
    return id;
  }

  private UUID insertUser() throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO users (id, subject, issuer, email, display_name, created_at,"
              + " organization_id) VALUES ('"
              + id
              + "', '"
              + id
              + "', 'issuer', 'user@example.com', 'User', now(), '"
              + SEEDED_ORGANIZATION_ID
              + "')");
    }
    return id;
  }

  private String sourceType(UUID libraryId) throws SQLException {
    return columnValue("source_type", libraryId);
  }

  private String columnValue(String column, UUID libraryId) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT " + column + " FROM knowledge_libraries WHERE id = '" + libraryId + "'")) {
      return result.next() ? result.getString(1) : null;
    }
  }
}
