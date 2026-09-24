package io.opaa.migration;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/089-branding-login-images.yaml} (#1910): the sign-in page's own
 * logo and its background image as two further slots of the branding singleton, each with the
 * all-or-nothing, content-type and size constraints the baseline's logo columns already carry.
 *
 * <p>The existing logo columns are asserted to be untouched: eight columns added to a table that
 * every page render reads is exactly the kind of change that can quietly break the one column
 * already in use.
 */
class Migration089BrandingLoginImagesTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/089-branding-login-images.yaml";

  /** A minimal PNG signature - enough for a constraint test; nothing here decodes an image. */
  private static final String PNG_BYTES = "decode('89504e470d0a1a0a', 'hex')";

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
  void beforeTheChangesetThereAreNoSignInImageColumns() throws Exception {
    assertThat(brandingColumns("login_%")).isZero();
  }

  @Test
  void theSingletonRowGainsBothSlotsAndKeepsItsLogoColumns() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(brandingColumns("login_logo_%")).isEqualTo(4);
    assertThat(brandingColumns("login_background_%")).isEqualTo(4);
    assertThat(brandingColumns("logo_%")).isEqualTo(4);
    assertThat(count("SELECT count(*) FROM branding_settings WHERE id = 1")).isEqualTo(1);
  }

  /** Both slots start out empty for an installation that had a logo before the migration. */
  @Test
  void anExistingLogoSurvivesAndTheNewSlotsStayEmpty() throws Exception {
    execute(
        "UPDATE branding_settings SET logo_content = "
            + PNG_BYTES
            + ", logo_content_type = 'image/png', logo_version = 'abc123', logo_updated_at = now()"
            + " WHERE id = 1");

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(
            count(
                "SELECT count(*) FROM branding_settings WHERE id = 1 AND logo_version = 'abc123'"
                    + " AND login_logo_content IS NULL AND login_background_content IS NULL"))
        .isEqualTo(1);
  }

  /** Each slot is complete or empty - a half-written image would be served as a broken one. */
  @Test
  void aPartiallyFilledSlotIsRejected() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(
            () ->
                execute(
                    "UPDATE branding_settings SET login_logo_content = "
                        + PNG_BYTES
                        + " WHERE id = 1"))
        .hasMessageContaining("chk_branding_settings_login_logo_complete");

    assertThatThrownBy(
            () ->
                execute(
                    "UPDATE branding_settings SET login_background_version = 'abc123' WHERE id ="
                        + " 1"))
        .hasMessageContaining("chk_branding_settings_login_background_complete");
  }

  /** The served content type is restricted in the schema too, not only in the validator. */
  @Test
  void aContentTypeOutsidePngAndJpegIsRejected() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(() -> storeLoginLogo("image/svg+xml"))
        .hasMessageContaining("chk_branding_settings_login_logo_content_type");

    storeLoginLogo("image/png");
    storeLoginLogo("image/jpeg");
  }

  /** The background's ceiling is the larger one; both are enforced in the schema. */
  @Test
  void eachSlotCarriesItsOwnSizeCeiling() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(() -> storeLoginLogoOfSize(512 * 1024 + 1))
        .hasMessageContaining("chk_branding_settings_login_logo_size");
    storeLoginLogoOfSize(512 * 1024);

    assertThatThrownBy(() -> storeBackgroundOfSize(2 * 1024 * 1024 + 1))
        .hasMessageContaining("chk_branding_settings_login_background_size");
    storeBackgroundOfSize(2 * 1024 * 1024);
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

  private void storeLoginLogo(String contentType) throws SQLException {
    execute(
        "UPDATE branding_settings SET login_logo_content = "
            + PNG_BYTES
            + ", login_logo_content_type = '"
            + contentType
            + "', login_logo_version = 'abc123', login_logo_updated_at = now() WHERE id = 1");
  }

  private void storeLoginLogoOfSize(int bytes) throws SQLException {
    execute(
        "UPDATE branding_settings SET login_logo_content = repeat('x', "
            + bytes
            + ")::bytea, login_logo_content_type = 'image/png', login_logo_version = 'abc123',"
            + " login_logo_updated_at = now() WHERE id = 1");
  }

  private void storeBackgroundOfSize(int bytes) throws SQLException {
    execute(
        "UPDATE branding_settings SET login_background_content = repeat('x', "
            + bytes
            + ")::bytea, login_background_content_type = 'image/jpeg', login_background_version ="
            + " 'abc123', login_background_updated_at = now() WHERE id = 1");
  }

  private long brandingColumns(String namePattern) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema()"
                + " AND table_name = 'branding_settings' AND column_name LIKE ?")) {
      statement.setString(1, namePattern);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  private void execute(String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
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
