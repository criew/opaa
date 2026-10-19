package io.opaa.migration;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/060-create-account-state-history.yaml} (#1818, ADR-0036
 * Entscheidung 8): the account-state history, which is at the same time the store of the current
 * state - the open interval - so there is no state column on {@code users} that could drift away
 * from it.
 */
class Migration060AccountStateHistoryTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/060-create-account-state-history.yaml";

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
    assertThat(deleteRuleOf("fk_account_state_history_actor_user_organization"))
        .isEqualTo("SET NULL");
    assertThat(foreignKeyOn("account_state_history", "provider_id"))
        .as("a provider is deletable; the evidence of what its run decided must survive that")
        .isFalse();
    assertThat(indexDefinition("uk_account_state_history_open"))
        .contains("(user_id)")
        .contains("valid_to IS NULL");
  }

  /**
   * The condition under which a never-used account stays deletable at all - access-control.md
   * promises exactly that one is. A backfill of one open interval per account would have made every
   * account of the installation permanently undeletable through {@code ON DELETE RESTRICT}.
   */
  @Test
  void noAccountGetsABackfillIntervalAndAnUntouchedAccountStaysDeletable() throws Exception {
    Fixture fixture = seedAccounts();

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(count("SELECT count(*) FROM account_state_history")).isZero();

    execute("DELETE FROM users WHERE id = ?", fixture.second());

    assertThat(count("SELECT count(*) FROM users WHERE id = '" + fixture.second() + "'")).isZero();
  }

  @Test
  void anAccountReferencedByTheHistoryCannotBeDeleted() throws Exception {
    Fixture fixture = seedAccounts();
    applyChangelog(connection, CHANGELOG_PATH);
    insertBlockedInterval(fixture, fixture.first());

    assertThatThrownBy(() -> execute("DELETE FROM users WHERE id = ?", fixture.first()))
        .hasMessageContaining("fk_account_state_history_user_organization");
  }

  @Test
  void anAccountCannotHoldTwoOpenIntervals() throws Exception {
    Fixture fixture = seedAccounts();
    applyChangelog(connection, CHANGELOG_PATH);
    insertBlockedInterval(fixture, fixture.first());

    assertThatThrownBy(() -> insertBlockedInterval(fixture, fixture.first()))
        .hasMessageContaining("uk_account_state_history_open");
    // A closed interval leaves the slot free again - that is what a reversal writes.
    execute("UPDATE account_state_history SET valid_to = now() WHERE user_id = ?", fixture.first());
    insertBlockedInterval(fixture, fixture.first());
  }

  /**
   * A row that claims the account was reactivated while carrying the state {@code BLOCKED} would be
   * evidence saying the opposite of what happened.
   */
  @Test
  void theCauseHasToMatchTheStateItClaims() throws Exception {
    Fixture fixture = seedAccounts();
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO account_state_history (id, user_id, organization_id, state,"
                        + " cause, valid_from) VALUES (?, ?, ?, 'BLOCKED',"
                        + " 'DIRECTORY_REACTIVATED', now())",
                    UUID.randomUUID(),
                    fixture.first(),
                    fixture.organization()))
        .hasMessageContaining("chk_account_state_history_cause_matches_state");
    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO account_state_history (id, user_id, organization_id, state,"
                        + " cause, valid_from) VALUES (?, ?, ?, 'ACTIVE', 'DIRECTORY_DISABLED',"
                        + " now())",
                    UUID.randomUUID(),
                    fixture.first(),
                    fixture.organization()))
        .hasMessageContaining("chk_account_state_history_cause_matches_state");
  }

  /**
   * Both value lists are closed. Which of the two checks Postgres names first is its own business -
   * a value outside either list violates both, since every cause belongs to exactly one state.
   */
  @Test
  void anUnknownStateOrCauseIsRejected() throws Exception {
    Fixture fixture = seedAccounts();
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO account_state_history (id, user_id, organization_id, state,"
                        + " cause, valid_from) VALUES (?, ?, ?, 'SUSPENDED',"
                        + " 'DIRECTORY_DISABLED', now())",
                    UUID.randomUUID(),
                    fixture.first(),
                    fixture.organization()))
        .hasMessageContaining("violates check constraint")
        .hasMessageContaining("chk_account_state_history_");
    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO account_state_history (id, user_id, organization_id, state,"
                        + " cause, valid_from) VALUES (?, ?, ?, 'BLOCKED', 'ADMIN_LOCK', now())",
                    UUID.randomUUID(),
                    fixture.first(),
                    fixture.organization()))
        .hasMessageContaining("violates check constraint")
        .hasMessageContaining("chk_account_state_history_");
  }

  /**
   * The organization boundary every personal column of this schema carries: the composite foreign
   * key refuses a row that names an account of another organization.
   */
  @Test
  void anAccountOfAnotherOrganizationCannotBeRecorded() throws Exception {
    Fixture fixture = seedAccounts();
    UUID otherOrganization = seedOrganization();
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO account_state_history (id, user_id, organization_id, state,"
                        + " cause, valid_from) VALUES (?, ?, ?, 'BLOCKED', 'DIRECTORY_ABSENT',"
                        + " now())",
                    UUID.randomUUID(),
                    fixture.first(),
                    otherOrganization))
        .hasMessageContaining("fk_account_state_history_user_organization");
  }

  @Test
  void theActorIsClearedRatherThanBlockingTheirOwnDeletion() throws Exception {
    Fixture fixture = seedAccounts();
    applyChangelog(connection, CHANGELOG_PATH);
    execute(
        "INSERT INTO account_state_history (id, user_id, organization_id, state, cause,"
            + " actor_user_id, valid_from) VALUES (?, ?, ?, 'BLOCKED', 'DIRECTORY_DISABLED', ?, ?)",
        UUID.randomUUID(),
        fixture.first(),
        fixture.organization(),
        fixture.second(),
        Timestamp.from(Instant.now()));

    execute("DELETE FROM users WHERE id = ?", fixture.second());

    assertThat(
            count(
                "SELECT count(*) FROM account_state_history WHERE user_id = '"
                    + fixture.first()
                    + "' AND actor_user_id IS NULL"))
        .isEqualTo(1);
  }

  @Test
  void theChangelogIsReferencedByTheMasterChangelog() throws Exception {
    assertThat(masterChangelog()).contains(CHANGELOG_PATH);
  }

  // -----------------------------------------------------------------------------------------
  // Fixture
  // -----------------------------------------------------------------------------------------

  private record Fixture(UUID organization, UUID first, UUID second) {}

  private Fixture seedAccounts() throws SQLException {
    UUID organization = seedOrganization();
    return new Fixture(organization, seedUser(organization), seedUser(organization));
  }

  private void insertBlockedInterval(Fixture fixture, UUID userId) throws SQLException {
    execute(
        "INSERT INTO account_state_history (id, user_id, organization_id, state, cause,"
            + " provider_id, valid_from) VALUES (?, ?, ?, 'BLOCKED', 'DIRECTORY_ABSENT', ?, ?)",
        UUID.randomUUID(),
        userId,
        fixture.organization(),
        UUID.randomUUID(),
        Timestamp.from(Instant.now()));
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
        "https://a.example",
        organization);
    return user;
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

  private boolean foreignKeyOn(String table, String column) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM pg_constraint fk"
                + " JOIN pg_class child ON child.oid = fk.conrelid"
                + " JOIN LATERAL unnest(fk.conkey) AS key_column(attnum) ON true"
                + " JOIN pg_attribute column_of_child"
                + "   ON column_of_child.attrelid = fk.conrelid"
                + "  AND column_of_child.attnum = key_column.attnum"
                + " WHERE fk.contype = 'f' AND child.relname = ?"
                + "   AND column_of_child.attname = ?")) {
      statement.setString(1, table);
      statement.setString(2, column);
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
}
