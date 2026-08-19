package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Applies Liquibase changelog 010 in isolation against a database pre-populated with legacy space
 * data - not against an empty schema. Follows the pattern established by {@code
 * Migration008RenameWorkspaceToSpaceTest}: apply the real, versioned changelog up to the changeSet
 * immediately preceding the new one (via {@code test-master-through-008.yaml}), seed representative
 * rows directly through JDBC, apply only the new changelog file, and assert on the resulting schema
 * and data.
 *
 * <p>Each {@code @Test} method gets its own database, freshly cloned from the class's {@code
 * test-master-through-008.yaml} template ({@link AbstractMigrationTest}) - so, unlike the original
 * shared-container version of this class, there is no risk of a later test method seeing changelog
 * 010 as "already applied" against an earlier method's seed data.
 */
class Migration010SpaceUniquenessTest extends AbstractMigrationTest {

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-008.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
  }

  @AfterEach
  void tearDown() throws SQLException {
    if (connection != null && !connection.isClosed()) {
      connection.close();
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
    applyChangelog(
        connection, "db/changelog/changes/010-space-uniqueness-and-membership-index.yaml");
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
