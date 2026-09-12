package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta test for {@code changes/012-mail-settings.yaml} (#1536, ADR-0033 Entscheidung 10): the
 * singleton SMTP configuration, its seeded row, and the three constraints that keep a second row,
 * an unknown encryption mode and an impossible port out of it.
 */
class Migration012MailSettingsTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH = "db/changelog/changes/012-mail-settings.yaml";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws SQLException {
    connection = connect();
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void createsTheSettingsTableWithItsColumnsAndSeedsTheSingletonRow() throws Exception {
    assertThat(tableExists()).isFalse();

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(tableExists()).isTrue();
    assertThat(columnNames())
        .containsExactlyInAnyOrder(
            "id",
            "enabled",
            "host",
            "port",
            "username",
            "password_ciphertext",
            "encryption",
            "from_address",
            "from_name",
            "last_success_at",
            "last_failure_at",
            "last_failure_reason",
            "updated_at",
            "updated_by");

    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT id, enabled, encryption, host, password_ciphertext FROM mail_settings")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt("id")).isEqualTo(1);
      // A fresh installation sends nothing and stores nothing: the row exists so the service never
      // has to create it, not so a deployment starts out configured.
      assertThat(rs.getBoolean("enabled")).isFalse();
      assertThat(rs.getString("encryption")).isEqualTo("STARTTLS");
      assertThat(rs.getString("host")).isNull();
      assertThat(rs.getString("password_ciphertext")).isNull();
      assertThat(rs.next()).isFalse();
    }
  }

  @Test
  void allowsOnlyTheOneRowWithIdOne() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(
            () -> execute("INSERT INTO mail_settings (id, updated_at) VALUES (2, now())"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_mail_settings_singleton");
  }

  @Test
  void rejectsAnEncryptionModeOutsideTheThreeTheApplicationKnows() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(() -> execute("UPDATE mail_settings SET encryption = 'TLSv13' WHERE id = 1"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_mail_settings_encryption");

    execute("UPDATE mail_settings SET encryption = 'SSL' WHERE id = 1");
    execute("UPDATE mail_settings SET encryption = 'NONE' WHERE id = 1");
  }

  @Test
  void rejectsAPortOutsideTheValidRangeButAcceptsNone() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(() -> execute("UPDATE mail_settings SET port = 70000 WHERE id = 1"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_mail_settings_port");
    assertThatThrownBy(() -> execute("UPDATE mail_settings SET port = 0 WHERE id = 1"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_mail_settings_port");

    execute("UPDATE mail_settings SET port = 587 WHERE id = 1");
    execute("UPDATE mail_settings SET port = NULL WHERE id = 1");
  }

  private void execute(String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private boolean tableExists() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT to_regclass('public.mail_settings') IS NOT NULL AS present")) {
      return rs.next() && rs.getBoolean("present");
    }
  }

  private List<String> columnNames() throws SQLException {
    List<String> names = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT column_name FROM information_schema.columns"
                    + " WHERE table_schema = 'public' AND table_name = 'mail_settings'")) {
      while (rs.next()) {
        names.add(rs.getString("column_name"));
      }
    }
    return names;
  }
}
