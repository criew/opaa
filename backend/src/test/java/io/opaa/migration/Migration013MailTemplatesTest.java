package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta test for {@code changes/013-mail-templates.yaml} (#1536, ADR-0033 Entscheidung 10): the
 * override table, its uniqueness per key and locale, and that it starts empty - which is what makes
 * "zurück zum Standard" a deletion rather than a re-seed.
 */
class Migration013MailTemplatesTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH = "db/changelog/changes/013-mail-templates.yaml";

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
  void createsTheOverrideTableEmptyWithItsColumns() throws Exception {
    assertThat(tableExists()).isFalse();

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(tableExists()).isTrue();
    assertThat(columnNames())
        .containsExactlyInAnyOrder(
            "id",
            "template_key",
            "locale",
            "subject",
            "body_plain",
            "body_html",
            "updated_at",
            "updated_by");
    // Deliberately no seeded rows: the delivered German text lives in MailTemplateKey, so deleting
    // an override restores it instead of leaving a template without content.
    assertThat(rowCount()).isZero();
  }

  @Test
  void allowsOneOverridePerKeyAndLocaleAndRefusesASecond() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    insert("PASSWORD_RESET", "de");
    insert("PASSWORD_RESET", "en");
    insert("ACCOUNT_LOCKED", "de");

    assertThatThrownBy(() -> insert("PASSWORD_RESET", "de"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ux_mail_templates_key_locale");
    assertThat(rowCount()).isEqualTo(3);
  }

  @Test
  void keepsTheHtmlBodyOptionalSoTheBrandedFrameStaysTheDefault() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    insert("TEST_MAIL", "de");

    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT body_html, updated_by, updated_at FROM mail_templates")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString("body_html")).isNull();
      assertThat(rs.getString("updated_by")).isNull();
      assertThat(rs.getTimestamp("updated_at")).isNotNull();
    }
  }

  private void insert(String templateKey, String locale) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO mail_templates (id, template_key, locale, subject, body_plain) VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + templateKey
              + "', '"
              + locale
              + "', 'Betreff', 'Text')");
    }
  }

  private boolean tableExists() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT to_regclass('public.mail_templates') IS NOT NULL AS present")) {
      return rs.next() && rs.getBoolean("present");
    }
  }

  private int rowCount() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT count(*) AS c FROM mail_templates")) {
      return rs.next() ? rs.getInt("c") : -1;
    }
  }

  private List<String> columnNames() throws SQLException {
    List<String> names = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT column_name FROM information_schema.columns"
                    + " WHERE table_schema = 'public' AND table_name = 'mail_templates'")) {
      while (rs.next()) {
        names.add(rs.getString("column_name"));
      }
    }
    return names;
  }
}
