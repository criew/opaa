package io.opaa.migration;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/057-create-group-stewards.yaml} (#1814, ADR-0036 Entscheidung 4):
 * the table that says who maintains an internal group, with the delete rules that decide what
 * happens when the group or the person goes.
 */
class Migration057GroupStewardsTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/057-create-group-stewards.yaml";

  private static final UUID DEFAULT_ORGANIZATION =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
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
  void beforeTheChangesetThereIsNoStewardshipTable() throws Exception {
    assertThat(
            count(
                "SELECT count(*) FROM information_schema.tables"
                    + " WHERE table_schema = current_schema() AND table_name = 'group_stewards'"))
        .isZero();
  }

  @Test
  void theTableCarriesOneRowPerPersonAndGroup() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID group = seedInternalGroup("Projektteam");
    UUID user = seedUser();

    seedSteward(group, user);

    assertThat(
            count(
                "SELECT count(*) FROM group_stewards WHERE group_id = '"
                    + group
                    + "' AND user_id = '"
                    + user
                    + "'"))
        .isEqualTo(1);
    assertThatThrownBy(() -> seedSteward(group, user))
        .hasMessageContaining("uk_group_stewards_group_user");
  }

  /**
   * Responsibility is a present-tense operating right, not a record that has to outlive its group:
   * the group's deletion takes it with it.
   */
  @Test
  void aStewardshipGoesWithItsGroup() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID group = seedInternalGroup("Projektteam");
    UUID user = seedUser();
    seedSteward(group, user);

    execute("DELETE FROM groups WHERE id = ?", group);

    assertThat(count("SELECT count(*) FROM group_stewards WHERE group_id = '" + group + "'"))
        .isZero();
    assertThat(count("SELECT count(*) FROM users WHERE id = '" + user + "'")).isEqualTo(1);
  }

  /**
   * Both person columns are RESTRICT after ADR-0016 - the account cannot be deleted out from under
   * its responsibility, and {@code UserRepository#countDeletionBlockers} names the table in the
   * refusal.
   */
  @Test
  void anAccountCannotBeDeletedWhileItIsResponsibleForAGroup() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID group = seedInternalGroup("Projektteam");
    UUID steward = seedUser();
    UUID appointer = seedUser();
    execute(
        "INSERT INTO group_stewards (id, group_id, user_id, organization_id, appointed_by_user_id)"
            + " VALUES (?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        group,
        steward,
        DEFAULT_ORGANIZATION,
        appointer);

    assertThatThrownBy(() -> execute("DELETE FROM users WHERE id = ?", steward))
        .hasMessageContaining("fk_group_stewards_user_organization");
    assertThatThrownBy(() -> execute("DELETE FROM users WHERE id = ?", appointer))
        .hasMessageContaining("fk_group_stewards_appointed_by_organization");
  }

  /** A group as a steward would be nesting through the back door - the column names a person. */
  @Test
  void onlyAnExistingAccountOfTheSameOrganizationCanBeAppointed() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID group = seedInternalGroup("Projektteam");

    assertThatThrownBy(() -> seedSteward(group, UUID.randomUUID()))
        .hasMessageContaining("fk_group_stewards_user_organization");
    assertThatCode(() -> seedSteward(group, seedUser())).doesNotThrowAnyException();
  }

  @Test
  void theChangelogIsReferencedByTheMasterChangelog() throws Exception {
    String master =
        new String(
            requireNonNull(
                    getClass()
                        .getClassLoader()
                        .getResourceAsStream("db/changelog/db.changelog-master.yaml"))
                .readAllBytes(),
            StandardCharsets.UTF_8);

    assertThat(master).contains(CHANGELOG_PATH);
  }

  // -----------------------------------------------------------------------------------------
  // Fixture
  // -----------------------------------------------------------------------------------------

  private UUID seedInternalGroup(String name) throws SQLException {
    UUID group = UUID.randomUUID();
    execute(
        "INSERT INTO groups (id, organization_id, kind, name) VALUES (?, ?, 'AD_HOC', ?)",
        group,
        DEFAULT_ORGANIZATION,
        name);
    return group;
  }

  private UUID seedUser() throws SQLException {
    UUID user = UUID.randomUUID();
    execute(
        "INSERT INTO users (id, organization_id, subject, issuer, email, display_name)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
        user,
        DEFAULT_ORGANIZATION,
        "subject-" + user,
        "https://idp.example/realms/a",
        user + "@example.com",
        "Test User");
    return user;
  }

  private void seedSteward(UUID groupId, UUID userId) throws SQLException {
    execute(
        "INSERT INTO group_stewards (id, group_id, user_id, organization_id) VALUES (?, ?, ?, ?)",
        UUID.randomUUID(),
        groupId,
        userId,
        DEFAULT_ORGANIZATION);
  }

  private void execute(String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      statement.executeUpdate();
    }
  }

  private long count(String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }
}
