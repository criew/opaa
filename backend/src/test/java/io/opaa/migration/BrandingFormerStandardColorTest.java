package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A stored accent equal to the former OPAA standard is reset to {@code null}, so the installation
 * follows the current standard; an operator's own colour stays.
 */
class BrandingFormerStandardColorTest extends AbstractMigrationTest {

  private static final String SCRIPT =
      "db/migration/identity/V2026.11.26.1__branding_former_standard_color.sql";

  @Override
  protected List<String> baseFixtureMigrations() {
    return Migrations.except(SCRIPT);
  }

  @Test
  void theFormerStandardBecomesUnset() throws SQLException {
    assertThat(colorAfterMigration("'#1292ee'")).isNull();
  }

  @Test
  void anOperatorColourStays() throws SQLException {
    assertThat(colorAfterMigration("'#7A1FA2'")).isEqualTo("#7A1FA2");
  }

  private String colorAfterMigration(String stored) throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO branding_settings (id, primary_color) VALUES (1, "
              + stored
              + ")"
              + " ON CONFLICT (id) DO UPDATE SET primary_color = EXCLUDED.primary_color");
      applyMigrations(connection, SCRIPT);
      try (ResultSet row =
          statement.executeQuery("SELECT primary_color FROM branding_settings WHERE id = 1")) {
        row.next();
        return row.getString(1);
      }
    }
  }
}
