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
 * Applies changelog 010 in isolation (#1532, ADR-0033 Entscheidung 3): the singleton {@code
 * local_auth_settings} row seeded with the ADR's defaults, the bounds the ADR names as CHECKs
 * (minimum password length never below 8, reset links 1-1440 minutes, inactivity at least 30 days)
 * and the {@code updated_by} reference that outlives the deleted administrator.
 */
class Migration010LocalAuthSettingsTest extends AbstractMigrationTest {

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    applyChangelog(connection, "db/changelog/changes/010-create-local-auth-settings.yaml");
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void seedsExactlyOneRowWithTheAdrDefaults() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT id, self_registration_enabled, self_registration_allowed_domains,"
                    + " password_reset_enabled, password_min_length, invitation_token_ttl_hours,"
                    + " reset_token_ttl_minutes, default_expiry_days, inactive_days, updated_at,"
                    + " updated_by, version FROM local_auth_settings")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getInt("id")).isEqualTo(1);
      assertThat(rows.getBoolean("self_registration_enabled")).isFalse();
      assertThat(rows.getString("self_registration_allowed_domains")).isEmpty();
      assertThat(rows.getBoolean("password_reset_enabled")).isTrue();
      assertThat(rows.getInt("password_min_length")).isEqualTo(12);
      assertThat(rows.getInt("invitation_token_ttl_hours")).isEqualTo(72);
      assertThat(rows.getInt("reset_token_ttl_minutes")).isEqualTo(30);
      assertThat(rows.getInt("default_expiry_days")).isEqualTo(90);
      assertThat(rows.getInt("inactive_days")).isEqualTo(90);
      assertThat(rows.getTimestamp("updated_at")).isNotNull();
      assertThat(rows.getObject("updated_by")).isNull();
      assertThat(rows.getLong("version")).isZero();
      assertThat(rows.next()).as("exactly one row").isFalse();
    }
  }

  @Test
  void rejectsASecondRow() {
    assertThatThrownBy(() -> execute("INSERT INTO local_auth_settings (id) VALUES (2)"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_local_auth_settings_singleton");
    assertThatThrownBy(() -> execute("INSERT INTO local_auth_settings (id) VALUES (1)"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("local_auth_settings_pkey");
  }

  @Test
  void keepsTheMinimumPasswordLengthBetweenEightAndSixtyFour() {
    assertThatThrownBy(() -> update("password_min_length = 7"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_local_auth_settings_password_min_length");
    assertThatThrownBy(() -> update("password_min_length = 65"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_local_auth_settings_password_min_length");
    assertThatCode(() -> update("password_min_length = 8")).doesNotThrowAnyException();
    assertThatCode(() -> update("password_min_length = 64")).doesNotThrowAnyException();
  }

  @Test
  void keepsResetLinkLifetimeBetweenOneMinuteAndOneDay() {
    assertThatThrownBy(() -> update("reset_token_ttl_minutes = 0"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_local_auth_settings_reset_token_ttl");
    assertThatThrownBy(() -> update("reset_token_ttl_minutes = 1441"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_local_auth_settings_reset_token_ttl");
    assertThatCode(() -> update("reset_token_ttl_minutes = 1440")).doesNotThrowAnyException();
  }

  @Test
  void keepsInvitationLinkLifetimeBetweenOneHourAndThirtyDays() {
    assertThatThrownBy(() -> update("invitation_token_ttl_hours = 0"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_local_auth_settings_invitation_token_ttl");
    assertThatThrownBy(() -> update("invitation_token_ttl_hours = 721"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_local_auth_settings_invitation_token_ttl");
  }

  @Test
  void keepsInactivityAtLeastThirtyDaysAndExpiryAtLeastOneDay() {
    assertThatThrownBy(() -> update("inactive_days = 29"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_local_auth_settings_inactive_days");
    assertThatThrownBy(() -> update("default_expiry_days = 0"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_local_auth_settings_default_expiry_days");
    assertThatCode(() -> update("inactive_days = 30, default_expiry_days = 1"))
        .doesNotThrowAnyException();
  }

  @Test
  void clearsUpdatedByWhenThatUserIsDeleted() throws SQLException {
    UUID admin = insertUser(connection, LOCAL_ISSUER, "admin@stadt.example");
    update("updated_by = '" + admin + "'");

    LocalAccountSchemaSupport.deleteUser(connection, admin);

    assertThat(count(connection, "local_auth_settings", "updated_by IS NULL")).isEqualTo(1);
  }

  private void update(String setClause) throws SQLException {
    execute("UPDATE local_auth_settings SET " + setClause + " WHERE id = 1");
  }

  private void execute(String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
