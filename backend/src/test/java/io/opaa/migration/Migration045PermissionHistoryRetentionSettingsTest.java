package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Applies changelog 045 in isolation (#1833, ADR-0036 Entscheidung 8): the singleton settings row
 * of the rights history's retention, seeded with the delivered 36 months, the bounds 12..120 as a
 * CHECK rather than only in the service, and the deletion progress seeded to the installation date
 * so a shortening cannot take effect retroactively on the first pass.
 */
class Migration045PermissionHistoryRetentionSettingsTest extends AbstractMigrationTest {

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  /**
   * The changelog is applied under a session zone far from UTC on purpose: the seeded month
   * boundary must be the UTC one, because the deletion pass computes its own target in UTC. A seed
   * that followed the session zone would sit hours ahead of that target for ever, and every pass
   * would report a period that is "not fully effective yet".
   */
  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    execute("SET TIME ZONE 'Pacific/Kiritimati'");
    applyChangelog(
        connection, "db/changelog/changes/045-create-permission-history-retention-settings.yaml");
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void seedsExactlyOneRowWithThreeYearsAndAProgressStartingNow() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT id, retention_months, last_cutoff, updated_at"
                    + " FROM permission_history_retention_settings")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getInt("id")).isEqualTo(1);
      assertThat(rows.getInt("retention_months"))
          .as("the delivered value is three years, not the ceiling")
          .isEqualTo(36);
      assertThat(rows.getTimestamp("last_cutoff"))
          .as("the deletion progress starts where the installation does, not at NULL")
          .isNotNull();
      assertThat(rows.getTimestamp("updated_at")).isNotNull();
      assertThat(rows.next()).as("exactly one row").isFalse();
    }
    assertThat(
            queryForBoolean(
                "SELECT last_cutoff = (date_trunc('month', updated_at AT TIME ZONE 'UTC')"
                    + " - interval '36 months') AT TIME ZONE 'UTC'"
                    + " FROM permission_history_retention_settings WHERE id = 1"))
        .as("the progress starts exactly one retention period before the installation month")
        .isTrue();
  }

  /** See {@link #setUp()} for why the session this runs in is fourteen hours off UTC. */
  @Test
  void seedsTheMonthBoundaryInUtcWhateverTheSessionZoneIs() throws SQLException {
    assertThat(
            queryForBoolean(
                "SELECT (last_cutoff AT TIME ZONE 'UTC')::time = time '00:00:00'"
                    + " AND date_part('day', last_cutoff AT TIME ZONE 'UTC') = 1"
                    + " FROM permission_history_retention_settings WHERE id = 1"))
        .as("midnight on the first of a month, read in UTC")
        .isTrue();
  }

  @Test
  void keepsThePeriodBetweenOneAndTenYears() {
    assertThatThrownBy(() -> update("retention_months = 11"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_permission_history_retention_months");
    assertThatThrownBy(() -> update("retention_months = 121"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_permission_history_retention_months");
    assertThatCode(() -> update("retention_months = 12")).doesNotThrowAnyException();
    assertThatCode(() -> update("retention_months = 120")).doesNotThrowAnyException();
  }

  @Test
  void rejectsASecondRow() {
    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO permission_history_retention_settings"
                        + " (id, retention_months, updated_at) VALUES (2, 36, now())"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_permission_history_retention_singleton");
    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO permission_history_retention_settings"
                        + " (id, retention_months, updated_at) VALUES (1, 36, now())"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission_history_retention_settings_pkey");
  }

  /**
   * The settings row is an ordinary application table, unlike {@code audit_retention_settings} and
   * {@code diagnostic_context_retention_settings}: ADR-0015's restricted ownership covers the two
   * protocols, and the three history tables this period governs are written by the application
   * account itself, so there is nothing here a second owner could withhold from it.
   */
  @Test
  void staysOwnedByTheMigrationAccountRatherThanTheProtocolOwner() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet owner =
            statement.executeQuery(
                "SELECT tableowner FROM pg_tables WHERE schemaname = current_schema()"
                    + " AND tablename = 'permission_history_retention_settings'")) {
      assertThat(owner.next()).isTrue();
      assertThat(owner.getString("tableowner")).isNotEqualTo("opaa_audit_owner");
    }
  }

  private boolean queryForBoolean(String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      assertThat(result.next()).isTrue();
      return result.getBoolean(1);
    }
  }

  private void update(String setClause) throws SQLException {
    execute("UPDATE permission_history_retention_settings SET " + setClause + " WHERE id = 1");
  }

  private void execute(String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
