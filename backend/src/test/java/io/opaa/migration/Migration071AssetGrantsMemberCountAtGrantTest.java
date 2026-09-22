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
 * Delta tests for {@code changes/071-asset-grants-member-count-at-grant.yaml} (#1820, ADR-0036
 * Entscheidung 9): the number of active accounts a group reached when a release was given. No stock
 * step, deliberately - for a release from before the column there is no right figure, and an
 * invented one would be worse than none.
 */
class Migration071AssetGrantsMemberCountAtGrantTest extends AbstractMigrationTest {

  private static final String TYPE_INDEPENDENT_PATH =
      "db/changelog/changes/038-asset-grants-type-independent.yaml";

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/071-asset-grants-member-count-at-grant.yaml";

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
    applyChangelog(connection, TYPE_INDEPENDENT_PATH);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void beforeTheChangesetAGrantCarriesNoGroupSize() throws Exception {
    assertThat(columnExists()).isFalse();
  }

  /**
   * A release from before the column keeps no figure - the view then shows today's number alone.
   */
  @Test
  void aStockGrantKeepsNoFigure() throws Exception {
    UUID group = seedInternalGroup("Referat 50");
    UUID grant = seedGroupGrant(group);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(
            count(
                "SELECT count(*) FROM asset_grants WHERE id = '"
                    + grant
                    + "' AND"
                    + " member_count_at_grant IS NULL"))
        .isEqualTo(1);
  }

  @Test
  void aGroupGrantCanCarryTheFigure() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID group = seedInternalGroup("Referat 50");
    UUID grant = seedGroupGrant(group);

    execute("UPDATE asset_grants SET member_count_at_grant = 23 WHERE id = ?", grant);

    assertThat(
            count(
                "SELECT count(*) FROM asset_grants WHERE id = '"
                    + grant
                    + "' AND"
                    + " member_count_at_grant = 23"))
        .isEqualTo(1);
  }

  /** A person has no group size - the check rejects a figure on a USER row. */
  @Test
  void aPersonGrantCannotCarryTheFigure() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID grant = seedUserGrant();

    assertThatThrownBy(
            () -> execute("UPDATE asset_grants SET member_count_at_grant = 3 WHERE id = ?", grant))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_asset_grants_member_count");
  }

  @Test
  void aNegativeFigureIsRejected() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID group = seedInternalGroup("Referat 50");
    UUID grant = seedGroupGrant(group);

    assertThatThrownBy(
            () -> execute("UPDATE asset_grants SET member_count_at_grant = -1 WHERE id = ?", grant))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_asset_grants_member_count");
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

  private UUID seedGroupGrant(UUID groupId) throws SQLException {
    UUID grant = UUID.randomUUID();
    execute(
        "INSERT INTO asset_grants (id, asset_type, asset_id, organization_id, subject_type,"
            + " subject_group_id, role) VALUES (?, 'KNOWLEDGE_LIBRARY', ?, ?, 'GROUP', ?, 'VIEWER')",
        grant,
        UUID.randomUUID(),
        DEFAULT_ORGANIZATION,
        groupId);
    return grant;
  }

  private UUID seedUserGrant() throws SQLException {
    UUID user = UUID.randomUUID();
    execute(
        "INSERT INTO users (id, subject, issuer, email, organization_id) VALUES (?, ?,"
            + " 'urn:opaa:local', ?, ?)",
        user,
        user.toString(),
        user + "@example.com",
        DEFAULT_ORGANIZATION);
    UUID grant = UUID.randomUUID();
    execute(
        "INSERT INTO asset_grants (id, asset_type, asset_id, organization_id, subject_type,"
            + " subject_user_id, role) VALUES (?, 'KNOWLEDGE_LIBRARY', ?, ?, 'USER', ?, 'VIEWER')",
        grant,
        UUID.randomUUID(),
        DEFAULT_ORGANIZATION,
        user);
    return grant;
  }

  private boolean columnExists() throws SQLException {
    return count(
            "SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema()"
                + " AND table_name = 'asset_grants' AND column_name = 'member_count_at_grant'")
        > 0;
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
