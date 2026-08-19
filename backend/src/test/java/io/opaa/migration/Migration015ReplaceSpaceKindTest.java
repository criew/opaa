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
 * Applies Liquibase changelog 015 in isolation against a database built from the real, versioned
 * changelog through changeSet 014 - the same pattern as {@code Migration014DropAssetRoleUserTest},
 * with {@code test-master-through-014.yaml} as the pre-migration fixture. {@code
 * connection.setAutoCommit(true)} is called after every {@code liquibase.update(...)} call, and the
 * public schema is dropped and recreated between test methods, per the package Javadoc's mandatory
 * teardown pattern.
 *
 * <p>The migration replaces {@code spaces.kind} with {@code spaces.is_default}. The tests cover the
 * two things that can silently go wrong on live data: a PERSONAL space that does not become the
 * owner's default space, and a gap in the "at most one default space per owner" guarantee while the
 * index moves from the old column to the new one.
 */
@Testcontainers(disabledWithoutDocker = true)
class Migration015ReplaceSpaceKindTest extends AbstractMigrationTest {

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-014.yaml";
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
  void aPersonalSpaceBecomesItsOwnersDefaultSpace() throws Exception {
    UUID owner = insertUser(UUID.randomUUID());
    UUID personal = insertSpace(UUID.randomUUID(), "PERSONAL", owner);

    applyChangelog015();

    assertThat(isDefault(personal)).isTrue();
  }

  @Test
  void projectAndTeamSpacesBecomeOrdinarySpaces() throws Exception {
    UUID owner = insertUser(UUID.randomUUID());
    UUID project = insertSpace(UUID.randomUUID(), "PROJECT", owner);
    UUID team = insertSpace(UUID.randomUUID(), "TEAM", owner);

    applyChangelog015();

    // No data is lost, only the label: both rows survive with is_default = false.
    assertThat(isDefault(project)).isFalse();
    assertThat(isDefault(team)).isFalse();
  }

  @Test
  void theKindColumnIsGone() throws Exception {
    applyChangelog015();

    try (Statement statement = connection.createStatement()) {
      assertThatThrownBy(() -> statement.execute("SELECT kind FROM spaces"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("kind");
    }
  }

  @Test
  void theUniqueIndexStillPreventsASecondDefaultSpacePerOwner() throws Exception {
    UUID owner = insertUser(UUID.randomUUID());
    insertSpace(UUID.randomUUID(), "PERSONAL", owner);

    applyChangelog015();

    // The guarantee migration 010 gave for PERSONAL must survive the move to is_default, otherwise
    // ensureDefaultSpace's ON CONFLICT clause has nothing to conflict with and a race creates two.
    assertThatThrownBy(() -> insertDefaultSpace(UUID.randomUUID(), owner))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_spaces_default_owner");
  }

  @Test
  void aSecondNonDefaultSpaceForTheSameOwnerStaysAllowed() throws Exception {
    UUID owner = insertUser(UUID.randomUUID());
    insertSpace(UUID.randomUUID(), "PERSONAL", owner);

    applyChangelog015();

    // #333's point: a user may own any number of spaces, including several they work in alone.
    // Only the default one is unique.
    insertNonDefaultSpace(UUID.randomUUID(), owner);
    insertNonDefaultSpace(UUID.randomUUID(), owner);

    assertThat(spaceCountFor(owner)).isEqualTo(3);
  }

  private void applyChangelog015() throws Exception {
    applyChangelog(connection, "db/changelog/changes/015-replace-space-kind-with-is-default.yaml");
  }

  private UUID insertUser(UUID id) throws SQLException {
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
    return id;
  }

  private UUID insertSpace(UUID id, String kind, UUID ownerId) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO spaces (id, name, description, kind, visibility, owner_id,"
              + " organization_id, created_at, updated_at) VALUES ('"
              + id
              + "', 'Space "
              + id
              + "', null, '"
              + kind
              + "', 'PRIVATE', '"
              + ownerId
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', now(), now())");
    }
    return id;
  }

  private void insertDefaultSpace(UUID id, UUID ownerId) throws SQLException {
    insertPostMigrationSpace(id, ownerId, true);
  }

  private void insertNonDefaultSpace(UUID id, UUID ownerId) throws SQLException {
    insertPostMigrationSpace(id, ownerId, false);
  }

  private void insertPostMigrationSpace(UUID id, UUID ownerId, boolean isDefault)
      throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO spaces (id, name, description, is_default, visibility, owner_id,"
              + " organization_id, created_at, updated_at) VALUES ('"
              + id
              + "', 'Space "
              + id
              + "', null, "
              + isDefault
              + ", 'PRIVATE', '"
              + ownerId
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', now(), now())");
    }
  }

  private boolean isDefault(UUID spaceId) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery("SELECT is_default FROM spaces WHERE id = '" + spaceId + "'")) {
      result.next();
      return result.getBoolean(1);
    }
  }

  private int spaceCountFor(UUID ownerId) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM spaces WHERE owner_id = '" + ownerId + "'")) {
      result.next();
      return result.getInt(1);
    }
  }
}
