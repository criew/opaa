package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A stored product name equal to the former OPAA standard is reset to {@code null}, so the
 * installation follows the current standard; an operator's own product name stays.
 */
class BrandingFormerStandardProductNameTest extends AbstractMigrationTest {

  private static final String SCRIPT =
      "db/migration/identity/V2026.11.26.3__branding_former_standard_product_name.sql";

  @Override
  protected List<String> baseFixtureMigrations() {
    return Migrations.except(SCRIPT);
  }

  @Test
  void theFormerStandardBecomesUnset() throws SQLException {
    assertThat(nameAfterMigration("'OPAA'")).isNull();
  }

  @Test
  void anOperatorNameStays() throws SQLException {
    assertThat(nameAfterMigration("'OPAA-Assistent'")).isEqualTo("OPAA-Assistent");
  }

  private String nameAfterMigration(String stored) throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO branding_settings (id, product_name) VALUES (1, "
              + stored
              + ")"
              + " ON CONFLICT (id) DO UPDATE SET product_name = EXCLUDED.product_name");
      applyMigrations(connection, SCRIPT);
      try (ResultSet row =
          statement.executeQuery("SELECT product_name FROM branding_settings WHERE id = 1")) {
        row.next();
        return row.getString(1);
      }
    }
  }
}
