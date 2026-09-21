package io.opaa.migration;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

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
 * Delta tests for {@code changes/063-users-directory-locked.yaml} (#1818, ADR-0036 Entscheidung 3):
 * the one stored fact the directory contributes about an account.
 */
class Migration063UsersDirectoryLockedTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/063-users-directory-locked.yaml";

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
  void beforeTheChangesetAnAccountCarriesNoDirectoryState() throws Exception {
    assertThat(columnExists("users", "directory_locked_at")).isFalse();
  }

  @Test
  void theChangesetAddsANullableTimestampAndLeavesEveryExistingAccountUnlocked() throws Exception {
    UUID organization = seedOrganization();
    UUID user = seedUser(organization);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(columnExists("users", "directory_locked_at")).isTrue();
    assertThat(isNullable("users", "directory_locked_at"))
        .as("null is 'not locked' - a default would lock or unlock every account at once")
        .isTrue();
    assertThat(
            count(
                "SELECT count(*) FROM users WHERE id = '"
                    + user
                    + "' AND directory_locked_at IS NULL"))
        .isEqualTo(1);
  }

  @Test
  void theChangesetAddsThePartialIndexOverTheLockedAccountsOfAProvider() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(indexDefinition("idx_users_directory_locked"))
        .contains("(organization_id, issuer)")
        .contains("directory_locked_at IS NOT NULL");
  }

  @Test
  void theChangelogIsReferencedByTheMasterChangelog() throws Exception {
    assertThat(masterChangelog()).contains(CHANGELOG_PATH);
  }

  // -----------------------------------------------------------------------------------------

  private UUID seedOrganization() throws SQLException {
    UUID organization = UUID.randomUUID();
    execute(
        "INSERT INTO organizations (id, name) VALUES (?, ?)",
        organization,
        "Organisation " + organization);
    return organization;
  }

  private UUID seedUser(UUID organization) throws SQLException {
    UUID user = UUID.randomUUID();
    execute(
        "INSERT INTO users (id, subject, issuer, organization_id) VALUES (?, ?, ?, ?)",
        user,
        "subject-" + user,
        "https://idp.example/realms/a",
        organization);
    return user;
  }

  private void execute(String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.executeUpdate();
    }
  }

  private long count(String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      assertThat(rows.next()).isTrue();
      return rows.getLong(1);
    }
  }

  private boolean columnExists(String table, String column) throws SQLException {
    return columnAttribute(table, column, "column_name") != null;
  }

  private boolean isNullable(String table, String column) throws SQLException {
    return "YES".equals(columnAttribute(table, column, "is_nullable"));
  }

  private String columnAttribute(String table, String column, String attribute)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT "
                + attribute
                + " FROM information_schema.columns WHERE table_schema = current_schema()"
                + " AND table_name = ? AND column_name = ?")) {
      statement.setString(1, table);
      statement.setString(2, column);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
      }
    }
  }

  private String indexDefinition(String name) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema()"
                + " AND indexname = ?")) {
      statement.setString(1, name);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getString(1);
      }
    }
  }

  private String masterChangelog() throws Exception {
    return new String(
        requireNonNull(
                getClass()
                    .getClassLoader()
                    .getResourceAsStream("db/changelog/db.changelog-master.yaml"))
            .readAllBytes(),
        StandardCharsets.UTF_8);
  }
}
