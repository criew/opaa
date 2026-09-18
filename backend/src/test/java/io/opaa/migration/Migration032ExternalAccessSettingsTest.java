package io.opaa.migration;

import static io.opaa.migration.LocalAccountSchemaSupport.LOCAL_ISSUER;
import static io.opaa.migration.LocalAccountSchemaSupport.count;
import static io.opaa.migration.LocalAccountSchemaSupport.insertUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Applies changelog 032 in isolation (#1717, ADR-0035): the singleton {@code
 * external_access_settings} row seeded with the delivered defaults - the channel <em>off</em>, 90
 * days, the conservative quota, the private address space as "Hausnetz" and the delivered
 * instructions text -, the bounds of every value as CHECKs and the {@code updated_by} reference
 * that outlives the deleted administrator.
 */
class Migration032ExternalAccessSettingsTest extends AbstractMigrationTest {

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    applyChangelog(connection, "db/changelog/changes/032-create-external-access-settings.yaml");
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void seedsExactlyOneRowWithTheDeliveredDefaultsAndTheChannelOff() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT id, enabled, token_max_lifetime_days, token_rate_limit_per_hour,"
                    + " allowed_cidrs, mass_retrieval_alert_threshold, server_instructions,"
                    + " updated_at, updated_by, version FROM external_access_settings")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getInt("id")).isEqualTo(1);
      assertThat(rows.getBoolean("enabled")).as("a fresh installation is closed").isFalse();
      assertThat(rows.getInt("token_max_lifetime_days")).isEqualTo(90);
      assertThat(rows.getInt("token_rate_limit_per_hour")).isEqualTo(60);
      assertThat(rows.getString("allowed_cidrs"))
          .isEqualTo("10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,127.0.0.0/8,::1/128,fc00::/7");
      assertThat(rows.getInt("mass_retrieval_alert_threshold")).isEqualTo(600);
      assertThat(rows.getString("server_instructions")).startsWith("Bei Fragen zu");
      assertThat(rows.getTimestamp("updated_at")).isNotNull();
      assertThat(rows.getObject("updated_by")).isNull();
      assertThat(rows.getLong("version")).isZero();
      assertThat(rows.next()).as("exactly one row").isFalse();
    }
  }

  @Test
  void rejectsASecondRow() {
    assertThatThrownBy(() -> execute("INSERT INTO external_access_settings (id) VALUES (2)"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_external_access_settings_singleton");
    assertThatThrownBy(() -> execute("INSERT INTO external_access_settings (id) VALUES (1)"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("external_access_settings_pkey");
  }

  @Test
  void keepsTheTokenLifetimeCeilingBetweenOneDayAndAYear() {
    assertThatThrownBy(() -> update("token_max_lifetime_days = 0"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_external_access_settings_token_lifetime");
    assertThatThrownBy(() -> update("token_max_lifetime_days = 366"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_external_access_settings_token_lifetime");
    assertThatCode(() -> update("token_max_lifetime_days = 365")).doesNotThrowAnyException();
  }

  @Test
  void keepsQuotaAndAlertThresholdPositiveAndBounded() {
    assertThatThrownBy(() -> update("token_rate_limit_per_hour = 0"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_external_access_settings_rate_limit");
    assertThatThrownBy(() -> update("token_rate_limit_per_hour = 10001"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_external_access_settings_rate_limit");
    assertThatThrownBy(() -> update("mass_retrieval_alert_threshold = 0"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_external_access_settings_alert_threshold");
    assertThatCode(
            () ->
                update(
                    "token_rate_limit_per_hour = 10000,"
                        + " mass_retrieval_alert_threshold = 1000000"))
        .doesNotThrowAnyException();
  }

  @Test
  void clearsUpdatedByWhenThatUserIsDeleted() throws SQLException {
    UUID admin = insertUser(connection, LOCAL_ISSUER, "verwaltung@stadt.example");
    update("updated_by = '" + admin + "'");

    LocalAccountSchemaSupport.deleteUser(connection, admin);

    assertThat(count(connection, "external_access_settings", "updated_by IS NULL")).isEqualTo(1);
  }

  private void update(String setClause) throws SQLException {
    execute("UPDATE external_access_settings SET " + setClause + " WHERE id = 1");
  }

  private void execute(String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
