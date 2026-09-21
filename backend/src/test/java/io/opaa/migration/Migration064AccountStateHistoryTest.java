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
 * Delta tests for {@code changes/064-create-account-state-history.yaml} (#1818, ADR-0036
 * Entscheidung 8): the account-state history, with the rule that keeps a never-locked account
 * deletable.
 */
class Migration064AccountStateHistoryTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/064-create-account-state-history.yaml";

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
  void beforeTheChangesetThereIsNoAccountStateHistoryAtAll() throws Exception {
    assertThat(tableExists("account_state_history")).isFalse();
  }

  @Test
  void theChangesetCreatesTheTableWithTheColumnRulesOfAdr0016() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(tableExists("account_state_history")).isTrue();
    assertThat(deleteRuleOf("fk_account_state_history_user_organization")).isEqualTo("RESTRICT");
    assertThat(indexDefinition("uk_account_state_history_open"))
        .contains("(user_id)")
        .contains("valid_to IS NULL");
  }

  /**
   * The condition under which an existing account stays deletable at all: the chain of an account
   * starts with its first state change, never with its creation - see changelog 064 and {@code
   * Migration052AssetOwnershipHistoryTest} for the same rule on the ownership side.
   */
  @Test
  void anAccountThatWasNeverLockedHasNoRowAndStaysDeletable() throws Exception {
    UUID organization = seedOrganization();
    UUID user = seedUser(organization);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(count("SELECT count(*) FROM account_state_history")).isZero();

    execute("DELETE FROM spaces WHERE owner_id = ?", user);
    execute("DELETE FROM users WHERE id = ?", user);

    assertThat(count("SELECT count(*) FROM users WHERE id = '" + user + "'")).isZero();
  }

  @Test
  void anAccountWithAHistoryRowCannotBeDeleted() throws Exception {
    UUID organization = seedOrganization();
    UUID user = seedUser(organization);

    applyChangelog(connection, CHANGELOG_PATH);
    insertInterval(user, organization, "LOCKED", "DIRECTORY_LOCKED", null);

    assertThatThrownBy(() -> execute("DELETE FROM users WHERE id = ?", user))
        .hasMessageContaining("fk_account_state_history_user_organization");
  }

  @Test
  void anAccountCannotHoldTwoOpenIntervals() throws Exception {
    UUID organization = seedOrganization();
    UUID user = seedUser(organization);

    applyChangelog(connection, CHANGELOG_PATH);
    insertInterval(user, organization, "LOCKED", "DIRECTORY_LOCKED", null);

    assertThatThrownBy(
            () -> insertInterval(user, organization, "ACTIVE", "DIRECTORY_UNLOCKED", null))
        .hasMessageContaining("uk_account_state_history_open");
    // A closed interval beside the open one is the normal shape of a chain.
    insertInterval(user, organization, "ACTIVE", "ACCOUNT_CREATED", "2026-01-01T00:00:00Z");
  }

  @Test
  void theChecksRejectAStateAndACauseTheEnumsDoNotKnow() throws Exception {
    UUID organization = seedOrganization();
    UUID user = seedUser(organization);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(
            () -> insertInterval(user, organization, "GESPERRT", "DIRECTORY_LOCKED", null))
        .hasMessageContaining("chk_account_state_history_state");
    assertThatThrownBy(() -> insertInterval(user, organization, "LOCKED", "ADMIN_LOCK", null))
        .hasMessageContaining("chk_account_state_history_cause");
  }

  @Test
  void theChangelogIsReferencedByTheMasterChangelog() throws Exception {
    assertThat(masterChangelog()).contains(CHANGELOG_PATH);
  }

  // -----------------------------------------------------------------------------------------

  private void insertInterval(
      UUID user, UUID organization, String state, String cause, String validTo)
      throws SQLException {
    execute(
        "INSERT INTO account_state_history (id, user_id, organization_id, state, cause,"
            + " valid_from, valid_to) VALUES (?, ?, ?, ?, ?, now(), CAST(? AS timestamptz))",
        UUID.randomUUID(),
        user,
        organization,
        state,
        cause,
        validTo);
  }

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

  private boolean tableExists(String table) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema()"
                + " AND table_name = ?")) {
      statement.setString(1, table);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }

  private String deleteRuleOf(String constraintName) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT confdeltype FROM pg_constraint WHERE conname = ? AND contype = 'f'")) {
      statement.setString(1, constraintName);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return switch (rows.getString(1)) {
          case "r" -> "RESTRICT";
          case "c" -> "CASCADE";
          case "n" -> "SET NULL";
          case "a" -> "NO ACTION";
          default -> rows.getString(1);
        };
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
