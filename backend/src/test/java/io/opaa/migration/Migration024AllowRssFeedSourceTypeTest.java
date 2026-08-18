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
 * changelog through changeSet 023 - the same pattern as {@code Migration020UploadMetadataTest} and
 * {@code Migration021AuditIncidentScopeGrantsTest}, with {@code test-master-through-023.yaml} as
 * the pre-migration fixture.
 *
 * <p>Covers #466's acceptance criterion that a document with {@code RSS_FEED} can be stored -
 * checked against a Liquibase-built schema, not a Hibernate-built one, because only the former
 * carries {@code chk_documents_source_type} at all (see AGENTS.md's reproduction-proof guidance on
 * schema parity between test and production).
 */
@Testcontainers(disabledWithoutDocker = true)
class Migration024AllowRssFeedSourceTypeTest {

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
            "db/changelog/test-master-through-023.yaml",
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
  void widenedCheckConstraintAcceptsRssFeedOnATableThatAlreadyHasLegacyRows() throws Exception {
    // A pre-existing FILESYSTEM row, written under the pre-024 constraint - the exact situation
    // "widen a CHECK on a table with existing data" needs to be proven against, not assumed.
    insertDocument("legacy.txt", "FILESYSTEM");

    applyChangelog024();

    UUID rssDoc = insertDocument("feed-entry.html", "RSS_FEED");
    assertThat(sourceType(rssDoc)).isEqualTo("RSS_FEED");
    assertThat(documentCount()).isEqualTo(2);
  }

  @Test
  void aValueOutsideTheWidenedSetIsStillRejected() throws Exception {
    applyChangelog024();

    assertThatThrownBy(() -> insertDocument("bogus.txt", "BOGUS"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_documents_source_type");
  }

  @Test
  void beforeTheMigrationRssFeedIsStillRejected() throws Exception {
    // Proves the constraint is genuinely load-bearing pre-migration, not vacuously satisfied.
    assertThatThrownBy(() -> insertDocument("too-early.html", "RSS_FEED"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_documents_source_type");
  }

  private void applyChangelog024() throws Exception {
    Liquibase liquibase =
        new Liquibase(
            "db/changelog/changes/024-allow-rss-feed-source-type.yaml",
            new ClassLoaderResourceAccessor(),
            database);
    liquibase.update(new Contexts());
    connection.setAutoCommit(true);
  }

  private UUID insertDocument(String fileName, String sourceType) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO documents (id, file_name, file_path, status, source_type, library_id,"
              + " organization_id) VALUES ('"
              + id
              + "', '"
              + fileName
              + "', '/corpus/"
              + fileName
              + "', 'INDEXED', '"
              + sourceType
              + "', '00000000-0000-0000-0000-000000000002', '"
              + SEEDED_ORGANIZATION_ID
              + "')");
    }
    return id;
  }

  private String sourceType(UUID documentId) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT source_type FROM documents WHERE id = '" + documentId + "'")) {
      return result.next() ? result.getString(1) : null;
    }
  }

  private int documentCount() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SELECT count(*) FROM documents")) {
      result.next();
      return result.getInt(1);
    }
  }
}
