package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A stored claim equal to the former OPAA standard is reset to {@code null}, so the installation
 * follows the current standard; an operator's own claim stays.
 */
class BrandingFormerStandardClaimTest extends AbstractMigrationTest {

  private static final String SCRIPT =
      "db/migration/identity/V2026.11.26.2__branding_former_standard_claim.sql";

  @Override
  protected List<String> baseFixtureMigrations() {
    return Migrations.except(SCRIPT);
  }

  @Test
  void theFormerStandardBecomesUnset() throws SQLException {
    assertThat(claimAfterMigration("'Fragen. Belegen. Entscheiden.'")).isNull();
  }

  @Test
  void anOperatorClaimStays() throws SQLException {
    assertThat(claimAfterMigration("'Fragen. Belegen.'")).isEqualTo("Fragen. Belegen.");
  }

  private String claimAfterMigration(String stored) throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO branding_settings (id, claim) VALUES (1, "
              + stored
              + ")"
              + " ON CONFLICT (id) DO UPDATE SET claim = EXCLUDED.claim");
      applyMigrations(connection, SCRIPT);
      try (ResultSet row =
          statement.executeQuery("SELECT claim FROM branding_settings WHERE id = 1")) {
        row.next();
        return row.getString(1);
      }
    }
  }
}
