package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
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
 * Applies Liquibase changelog 010 in isolation against a database pre-populated with legacy space
 * data - not against an empty schema. Follows the pattern established by {@code
 * Migration008RenameWorkspaceToSpaceTest}: apply the real, versioned changelog up to the changeSet
 * immediately preceding the new one (via {@code test-master-through-008.yaml}), seed representative
 * rows directly through JDBC, apply only the new changelog file, and assert on the resulting schema
 * and data.
 *
 * <p>Unlike {@code Migration008RenameWorkspaceToSpaceTest}, this class has more than one test
 * method. The shared static container is not reset between test methods by Testcontainers, and
 * Liquibase records every changeSet it applies in DATABASECHANGELOG - so a second {@code
 * liquibase.update(...)} call in a later test method would silently skip changelog 010 as "already
 * applied" against data seeded by an earlier method, instead of running against that method's own
 * fixture. {@link #resetSchema()} drops and recreates the public schema after every test so each
 * method starts from a schema-less database and reapplies changelog 010 against only its own seed
 * data. This is the gap flagged against {@code Migration008RenameWorkspaceToSpaceTest} for future
 * data-migration tests (see #237, #238) - this class is the first to close it.
 */
@Testcontainers(disabledWithoutDocker = true)
class Migration010SpaceUniquenessTest {

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

    // Apply everything up to (and including) changeSet 008 - the schema exactly as it existed
    // immediately before this migration - via the real, versioned changelog files.
    Liquibase liquibase =
        new Liquibase(
            "db/changelog/test-master-through-008.yaml",
            new ClassLoaderResourceAccessor(),
            database);
    liquibase.update(new Contexts());

    // Liquibase leaves auto-commit disabled on the connection after update(). Re-enable it so
    // that every raw JDBC statement below (seeding, applying changelog 010, assertions) commits
    // independently, matching how the application actually uses the database - one failing
    // statement, such as the deliberate constraint violation asserted below, must not abort every
    // later statement in the same test method.
    connection.setAutoCommit(true);
  }

  @AfterEach
  void tearDown() throws SQLException {
    resetSchema();
    if (connection != null && !connection.isClosed()) {
      connection.close();
    }
  }

  /**
   * Drops and recreates the public schema so the next test method's {@link #setUp()} starts from a
   * schema-less database, including an empty DATABASECHANGELOG - without this, Liquibase would
   * treat changelog 010 as already applied in every test after the first.
   *
   * <p>Liquibase leaves the connection's auto-commit disabled after {@code update()} - without
   * explicitly re-enabling it first, the DROP/CREATE below would run inside an uncommitted
   * transaction that gets silently rolled back when the connection closes in {@link #tearDown()},
   * leaving the previous test method's schema (including its unique index) in place for the next
   * one.
   */
  private void resetSchema() throws SQLException {
    connection.setAutoCommit(true);
    try (Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA public CASCADE");
      statement.execute("CREATE SCHEMA public");
    }
  }

  @Test
  void removesDuplicatePersonalSpacesAndEnforcesOnePersonalSpacePerOwner() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID otherOwner = UUID.randomUUID();
    insertUser(owner);
    insertUser(otherOwner);

    // Two personal spaces for the same owner - the exact scenario #265 describes, e.g. produced
    // by two concurrent first logins before this migration existed. oldestPersonal must survive;
    // newestDuplicate must be removed.
    UUID oldestPersonal = UUID.randomUUID();
    UUID newestDuplicate = UUID.randomUUID();
    UUID otherOwnerPersonal = UUID.randomUUID();
    Instant older = Instant.parse("2024-01-01T00:00:00Z");
    Instant newer = Instant.parse("2024-01-01T00:05:00Z");
    insertSpace(oldestPersonal, "Meine Dokumente", "PERSONAL", owner, older);
    insertSpace(newestDuplicate, "Meine Dokumente", "PERSONAL", owner, newer);
    insertSpace(otherOwnerPersonal, "Meine Dokumente", "PERSONAL", otherOwner, older);
    insertMembership(oldestPersonal, owner);
    insertMembership(newestDuplicate, owner);
    insertMembership(otherOwnerPersonal, otherOwner);

    applyChangelog010();

    assertThat(spaceExists(oldestPersonal)).isTrue();
    assertThat(spaceExists(newestDuplicate)).isFalse();
    assertThat(spaceExists(otherOwnerPersonal)).isTrue();
    assertThat(countRows("spaces")).isEqualTo(2);
    // The membership of the removed duplicate must be gone too, via ON DELETE CASCADE - not left
    // behind as an orphan pointing at a space that no longer exists.
    assertThat(countRows("space_memberships")).isEqualTo(2);

    assertThat(indexExists("uk_spaces_personal_owner")).isTrue();

    // The index must actually be enforced: inserting a second personal space for an owner that
    // already has one must fail, exactly the guarantee #265 requires.
    UUID secondPersonalForOwner = UUID.randomUUID();
    assertThatThrownBy(
            () -> insertSpace(secondPersonalForOwner, "Zweiter Space", "PERSONAL", owner, newer))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_spaces_personal_owner");

    // A user may still own any number of PROJECT spaces - the partial index must not restrict
    // that.
    insertSpace(UUID.randomUUID(), "Projekt A", "PROJECT", owner, newer);
    insertSpace(UUID.randomUUID(), "Projekt B", "PROJECT", owner, newer);
  }

  @Test
  void createsStandaloneIndexOnSpaceMembershipsSpaceId() throws Exception {
    applyChangelog010();

    assertThat(indexExists("idx_space_memberships_space_id")).isTrue();
  }

  private void applyChangelog010() throws Exception {
    Liquibase liquibase =
        new Liquibase(
            "db/changelog/changes/010-space-uniqueness-and-membership-index.yaml",
            new ClassLoaderResourceAccessor(),
            database);
    liquibase.update(new Contexts());
    // See the comment in setUp() - Liquibase disables auto-commit again on every update() call.
    connection.setAutoCommit(true);
  }

  private void insertUser(UUID id) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO users (id, subject, issuer, system_role, organization_id, created_at) "
              + "VALUES ('"
              + id
              + "', '"
              + id
              + "', 'test-issuer', 'USER', '"
              + SEEDED_ORGANIZATION_ID
              + "', now())");
    }
  }

  private void insertSpace(UUID id, String name, String kind, UUID ownerId, Instant createdAt)
      throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO spaces "
              + "(id, name, kind, visibility, owner_id, organization_id, created_at, updated_at) "
              + "VALUES ('"
              + id
              + "', '"
              + name
              + "', '"
              + kind
              + "', 'PRIVATE', '"
              + ownerId
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', '"
              + createdAt
              + "', '"
              + createdAt
              + "')");
    }
  }

  private void insertMembership(UUID spaceId, UUID userId) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO space_memberships "
              + "(id, user_id, space_id, role, organization_id, created_at) "
              + "VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + userId
              + "', '"
              + spaceId
              + "', 'ADMIN', '"
              + SEEDED_ORGANIZATION_ID
              + "', now())");
    }
  }

  private boolean spaceExists(UUID id) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT 1 FROM spaces WHERE id = '" + id + "'")) {
      return rs.next();
    }
  }

  private long countRows(String table) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private boolean indexExists(String indexName) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT 1 FROM pg_indexes WHERE indexname = '" + indexName + "'")) {
      return rs.next();
    }
  }
}
