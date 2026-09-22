package io.opaa.migration;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
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
 * Delta tests for {@code changes/077-create-group-contacts.yaml} (#1875, ADR-0036 Entscheidung 9):
 * the contact point of a provider group - one row per person and group, gone with the group, and
 * holding the account it names as long as it exists.
 */
class Migration077GroupContactsTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/077-create-group-contacts.yaml";

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
  void beforeTheChangesetThereIsNoTable() throws Exception {
    assertThat(tableCount()).isZero();
  }

  @Test
  void onePersonIsTheContactPointOfOneGroupExactlyOnce() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID group = seedGroup();
    UUID user = seedUser();
    insertContact(group, user);

    assertThatThrownBy(() -> insertContact(group, user))
        .hasMessageContaining("uk_group_contacts_group_user");
  }

  /** Betriebsrecht der Gegenwart: with the group, the appointment for it becomes pointless. */
  @Test
  void theAppointmentGoesWithTheGroup() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID group = seedGroup();
    insertContact(group, seedUser());

    execute("DELETE FROM groups WHERE id = ?", group);

    assertThat(count("SELECT count(*) FROM group_contacts")).isZero();
  }

  /**
   * ADR-0016: the two person columns are RESTRICT, so an account stays deletable only without one.
   */
  @Test
  void theAppointmentHoldsTheAccountsItNames() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID group = seedGroup();
    UUID contact = seedUser();
    UUID admin = seedUser();
    execute(
        "INSERT INTO group_contacts (id, group_id, user_id, organization_id,"
            + " appointed_by_user_id) VALUES (?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        group,
        contact,
        DEFAULT_ORGANIZATION,
        admin);

    assertThatThrownBy(() -> execute("DELETE FROM users WHERE id = ?", contact))
        .hasMessageContaining("fk_group_contacts_user_organization");
    assertThatThrownBy(() -> execute("DELETE FROM users WHERE id = ?", admin))
        .hasMessageContaining("fk_group_contacts_appointed_by_organization");
  }

  /** The organization boundary of every own object: composite keys, never the id alone. */
  @Test
  void aForeignOrganizationsGroupIsNoTarget() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID foreignOrganization = UUID.randomUUID();
    execute(
        "INSERT INTO organizations (id, name) VALUES (?, ?)",
        foreignOrganization,
        "Nachbarhaus " + foreignOrganization);
    UUID foreignGroup = UUID.randomUUID();
    execute(
        "INSERT INTO groups (id, organization_id, kind, name) VALUES (?, ?, 'AD_HOC', 'Fremd')",
        foreignGroup,
        foreignOrganization);

    assertThatThrownBy(() -> insertContact(foreignGroup, seedUser()))
        .hasMessageContaining("fk_group_contacts_group_organization");
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

  private void insertContact(UUID groupId, UUID userId) throws SQLException {
    execute(
        "INSERT INTO group_contacts (id, group_id, user_id, organization_id) VALUES (?, ?, ?, ?)",
        UUID.randomUUID(),
        groupId,
        userId,
        DEFAULT_ORGANIZATION);
  }

  private UUID seedGroup() throws SQLException {
    UUID group = UUID.randomUUID();
    execute(
        "INSERT INTO groups (id, organization_id, kind, name) VALUES (?, ?, 'ORG_UNIT', ?)",
        group,
        DEFAULT_ORGANIZATION,
        "Referat " + group);
    return group;
  }

  private UUID seedUser() throws SQLException {
    UUID user = UUID.randomUUID();
    execute(
        "INSERT INTO users (id, subject, issuer, email, organization_id) VALUES (?, ?,"
            + " 'https://issuer.example.org', ?, ?)",
        user,
        user.toString(),
        user + "@example.org",
        DEFAULT_ORGANIZATION);
    return user;
  }

  private long tableCount() throws SQLException {
    return count(
        "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema()"
            + " AND table_name = 'group_contacts'");
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
