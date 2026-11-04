package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Dropping the operator's default colour scheme on an existing installation: the branding row keeps
 * its other values, the column and its check are gone.
 */
class BrandingDefaultColorSchemeDropMigrationTest extends AbstractBaselineTest {

  private static final String SCRIPT =
      "db/migration/identity/V2026.11.04.7__contract_drop_branding_default_color_scheme.sql";

  @Override
  protected List<String> baseFixtureMigrations() {
    return Migrations.except(SCRIPT);
  }

  @Test
  void theBrandingRowSurvivesWithoutTheColourScheme() throws SQLException {
    execute(
        "UPDATE branding_settings SET product_name = 'Landesamt-Assistent',"
            + " primary_color = '#7A3E9D', default_color_scheme = 'DARK' WHERE id = 1");

    applyMigrations(connection, SCRIPT);

    assertThat(stringOf("SELECT product_name || ':' || primary_color FROM branding_settings"))
        .isEqualTo("Landesamt-Assistent:#7A3E9D");
    assertThat(
            countWhere(
                "information_schema.columns",
                "table_schema = current_schema() AND table_name = 'branding_settings'"
                    + " AND column_name = 'default_color_scheme'"))
        .isZero();
    assertThat(
            countWhere(
                "pg_constraint",
                "conname = 'chk_branding_settings_color_scheme'"
                    + " AND connamespace = current_schema()::regnamespace"))
        .isZero();
  }
}
