package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Applies Liquibase changelog 037 in isolation against a database built from the real, versioned
 * changelog through changeSet 034 - the same pattern {@code Migration034AddChatTitleSourceTest}
 * follows, with {@code test-master-through-034.yaml} as the pre-migration fixture.
 *
 * <p>#543: {@code spaces.archived} is the maintainer-decided way out of a space that {@code
 * fk_chats_space} (ON DELETE RESTRICT, migration 032) makes permanently undeletable because it
 * still contains a chat authored by someone other than the space owner. This class proves the
 * column exists after 037 runs, defaults to {@code false} for both pre-existing and newly inserted
 * rows, and that the rollback restores the pre-migration schema.
 */
class Migration037AddArchivedToSpacesTest extends AbstractMigrationTest {

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-034.yaml";
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
  void addsTheArchivedColumnDefaultingToFalseForAnExistingSpace() throws Exception {
    UUID owner = insertUser();
    UUID space = insertSpace(owner);

    applyChangelog037();

    assertThat(hasArchivedColumn()).isTrue();
    assertThat(archived(space)).isFalse();
  }

  @Test
  void aNewlyInsertedSpaceDefaultsToNotArchived() throws Exception {
    applyChangelog037();
    UUID owner = insertUser();
    UUID space = insertSpace(owner);

    assertThat(archived(space)).isFalse();
  }

  @Test
  void rollbackRestoresThePreMigrationSchema() throws Exception {
    applyChangelog037();
    assertThat(hasArchivedColumn()).isTrue();

    Liquibase liquibase =
        new Liquibase(
            "db/changelog/changes/037-add-archived-to-spaces.yaml",
            new ClassLoaderResourceAccessor(),
            liquibaseDatabase(connection));
    liquibase.rollback(1, (String) null);
    connection.setAutoCommit(true);

    assertThat(hasArchivedColumn()).isFalse();
  }

  private void applyChangelog037() throws Exception {
    applyChangelog(connection, "db/changelog/changes/037-add-archived-to-spaces.yaml");
  }

  private boolean hasArchivedColumn() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM information_schema.columns"
                    + " WHERE table_name = 'spaces' AND column_name = 'archived'")) {
      result.next();
      return result.getInt(1) == 1;
    }
  }

  private boolean archived(UUID spaceId) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery("SELECT archived FROM spaces WHERE id = '" + spaceId + "'")) {
      result.next();
      return result.getBoolean(1);
    }
  }

  private UUID insertUser() throws SQLException {
    UUID id = UUID.randomUUID();
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

  private UUID insertSpace(UUID ownerId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO spaces "
              + "(id, name, is_default, visibility, owner_id, organization_id, created_at,"
              + " updated_at) "
              + "VALUES ('"
              + id
              + "', 'Fachbereich', false, 'PRIVATE', '"
              + ownerId
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', now(), now())");
    }
    return id;
  }
}
