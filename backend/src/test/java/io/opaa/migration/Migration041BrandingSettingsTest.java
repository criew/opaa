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
 * Applies Liquibase changelog 041 in isolation (#582). {@code branding_settings} depends on no
 * other table, so the fixture chain only has to produce a realistic database to apply it into -
 * {@code test-master-through-007.yaml} is the smallest one that does.
 *
 * <p>What this proves that {@code BrandingSettingsServiceIntegrationTest} cannot: the constraints
 * hold against direct SQL, not merely against writes that went through the service. That
 * distinction is the whole point of having them - the service is the primary defense, the database
 * is what a future write path cannot talk its way around.
 */
class Migration041BrandingSettingsTest extends AbstractMigrationTest {

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-007.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    applyChangelog(connection, "db/changelog/changes/041-create-branding-settings.yaml");
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void seedsExactlyOneRowWithNothingConfigured() throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT id, product_name, claim, primary_color, default_color_scheme,"
                    + " logo_content, updated_at FROM branding_settings")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getInt("id")).isEqualTo(1);
      // NULL means "never configured", not "empty" - the application resolves each field to the
      // OPAA default at read time (see the changelog's header comment for why it is not seeded).
      assertThat(rows.getString("product_name")).isNull();
      assertThat(rows.getString("claim")).isNull();
      assertThat(rows.getString("primary_color")).isNull();
      assertThat(rows.getString("default_color_scheme")).isNull();
      assertThat(rows.getBytes("logo_content")).isNull();
      assertThat(rows.getTimestamp("updated_at")).isNotNull();
      assertThat(rows.next()).as("exactly one row").isFalse();
    }
  }

  @Test
  void refusesASecondRow() {
    assertThatThrownBy(() -> execute("INSERT INTO branding_settings (id) VALUES (2)"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_branding_settings_singleton");
  }

  @Test
  void acceptsASixDigitHexColourAndRejectsAnythingElse() {
    assertThatCode(() -> update("primary_color = '#1292EE'")).doesNotThrowAnyException();

    assertThatThrownBy(() -> update("primary_color = 'blau'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_branding_settings_primary_color");
    assertThatThrownBy(() -> update("primary_color = '#12E'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_branding_settings_primary_color");
  }

  @Test
  void acceptsTheThreeColourSchemesAndRejectsAnythingElse() {
    for (String scheme : new String[] {"LIGHT", "DARK", "SYSTEM"}) {
      assertThatCode(() -> update("default_color_scheme = '" + scheme + "'"))
          .as("colour scheme %s", scheme)
          .doesNotThrowAnyException();
    }

    assertThatThrownBy(() -> update("default_color_scheme = 'NEONGRUEN'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_branding_settings_color_scheme");
  }

  @Test
  void acceptsAPngOrJpegLogoAndRejectsAnyOtherMediaType() {
    assertThatCode(() -> storeLogo("image/png", 64)).doesNotThrowAnyException();
    assertThatCode(() -> storeLogo("image/jpeg", 64)).doesNotThrowAnyException();

    assertThatThrownBy(() -> storeLogo("image/svg+xml", 64))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_branding_settings_logo_content_type");
  }

  @Test
  void rejectsALogoAboveTheSizeLimit() {
    assertThatThrownBy(() -> storeLogo("image/png", 512 * 1024 + 1))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_branding_settings_logo_size");
  }

  @Test
  void refusesLogoColumnsThatAreOnlyPartlySet() {
    assertThatThrownBy(() -> update("logo_content = decode('89504e47', 'hex')"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_branding_settings_logo_complete");
    assertThatThrownBy(() -> update("logo_content_type = 'image/png'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_branding_settings_logo_complete");
  }

  private void storeLogo(String contentType, int sizeBytes) throws SQLException {
    update(
        "logo_content = repeat('\\000', "
            + sizeBytes
            + ")::bytea, logo_content_type = '"
            + contentType
            + "', logo_version = 'abc123', logo_updated_at = now()");
  }

  private void update(String assignment) throws SQLException {
    execute("UPDATE branding_settings SET " + assignment + " WHERE id = 1");
  }

  private void execute(String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
