package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The quick-tour hint marker on an existing installation: every account present before reads {@code
 * false} afterwards, and an account inserted the way the previous version inserts it - without
 * naming the column - gets {@code false} as well, so that version keeps running beside it.
 */
class UsersQuickTourHintDoneMigrationTest extends AbstractBaselineTest {

  private static final String SCRIPT =
      "db/migration/identity/V2026.11.07.38__users_quick_tour_hint_done.sql";

  @Override
  protected List<String> baseFixtureMigrations() {
    return Migrations.except(SCRIPT);
  }

  @Test
  void existingAccountsStartAtFalseAndThePreviousVersionKeepsInsertingAccounts()
      throws SQLException {
    UUID existing = insertUser();

    applyMigrations(connection, SCRIPT);

    assertThat(doneOf(existing)).isFalse();
    UUID insertedByThePreviousVersion = insertUser();
    assertThat(doneOf(insertedByThePreviousVersion)).isFalse();
    assertThat(
            stringOf(
                "SELECT is_nullable FROM information_schema.columns WHERE table_name = 'users'"
                    + " AND column_name = 'quick_tour_hint_done'"))
        .isEqualTo("NO");
  }

  private boolean doneOf(UUID account) throws SQLException {
    return booleanOf("SELECT quick_tour_hint_done FROM users WHERE id = " + quoted(account));
  }
}
