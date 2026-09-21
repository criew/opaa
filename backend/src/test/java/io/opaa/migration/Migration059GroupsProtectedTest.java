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
 * Delta tests for {@code changes/059-groups-protected.yaml} (#1814, ADR-0036 Entscheidung 9): the
 * protection mark of the staff council and the comparable bodies. No stock step, deliberately - the
 * mark is set by the body concerned, and the stock knows nobody who could have decided it.
 */
class Migration059GroupsProtectedTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH = "db/changelog/changes/059-groups-protected.yaml";

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
  void beforeTheChangesetThereIsNoProtectionColumn() throws Exception {
    assertThat(
            count(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema ="
                    + " current_schema() AND table_name = 'groups' AND column_name = 'protected'"))
        .isZero();
  }

  /** Every group of the stock keeps the mark unset - nobody decided anything about it yet. */
  @Test
  void aStockGroupIsNotProtected() throws Exception {
    UUID group = seedInternalGroup("Personalrat");

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(protectedRows(group)).isZero();
  }

  @Test
  void theMarkCanBeSetAndTakenBack() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID group = seedInternalGroup("Personalrat");

    execute("UPDATE groups SET protected = true WHERE id = ?", group);
    assertThat(protectedRows(group)).isEqualTo(1);

    execute("UPDATE groups SET protected = false WHERE id = ?", group);
    assertThat(protectedRows(group)).isZero();
  }

  /** NOT NULL with a default: no group can end up with an undecided mark. */
  @Test
  void theColumnIsNotNullable() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(
            stringOf(
                "SELECT is_nullable FROM information_schema.columns WHERE table_schema ="
                    + " current_schema() AND table_name = 'groups' AND column_name = 'protected'"))
        .isEqualTo("NO");
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

  private long protectedRows(UUID groupId) throws SQLException {
    return count("SELECT count(*) FROM groups WHERE id = '" + groupId + "' AND protected");
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

  private String stringOf(String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getString(1);
    }
  }
}
