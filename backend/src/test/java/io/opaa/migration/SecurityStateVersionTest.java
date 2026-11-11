package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The version columns of accounts, sign-in providers, groups and connector type policies, added to
 * an installation with rows: every existing row starts at 0, and the predecessor version, which
 * neither inserts nor updates the column during a rolling update, still writes valid rows.
 */
class SecurityStateVersionTest extends AbstractBaselineTest {

  private static final List<String> SCRIPTS =
      List.of(
          "db/migration/identity/V2026.11.11.37__account_provider_version.sql",
          "db/migration/rights/V2026.11.11.38__group_version.sql",
          "db/migration/connections/V2026.11.11.39__connector_type_policy_version.sql");

  @Override
  protected List<String> baseFixtureMigrations() {
    return Migrations.except(SCRIPTS.toArray(String[]::new));
  }

  @Test
  void existingRowsStartAtZeroAndThePredecessorsWritesStayValid() throws Exception {
    UUID user = insertUser();
    UUID provider = insertProvider();
    UUID group = insertInternalGroup();
    String type = "VERSION_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    insertPolicy(type);

    applyMigrations(connection, SCRIPTS);

    assertThat(versionOf("users", "id", user)).isZero();
    assertThat(versionOf("oidc_providers", "id", provider)).isZero();
    assertThat(versionOf("groups", "id", group)).isZero();
    assertThat(versionOf("connector_type_policies", "source_type", type)).isZero();

    UUID insertedByPredecessor = insertUser();
    String typeOfPredecessor =
        "VERSION_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    insertPolicy(typeOfPredecessor);
    execute("UPDATE users SET system_role = 'SYSTEM_ADMIN' WHERE id = '" + user + "'");
    assertThat(versionOf("users", "id", insertedByPredecessor)).isZero();
    assertThat(versionOf("connector_type_policies", "source_type", typeOfPredecessor)).isZero();
    assertThat(versionOf("users", "id", user)).isZero();
    assertThat(versionOf("groups", "id", insertInternalGroup())).isZero();
    assertThat(versionOf("oidc_providers", "id", insertProvider())).isZero();
  }

  private void insertPolicy(String type) throws SQLException {
    execute(
        "INSERT INTO connector_type_policies (source_type, updated_at) VALUES ('"
            + type
            + "', now())");
  }

  private long versionOf(String table, String key, Object id) throws SQLException {
    return longOf("SELECT version FROM " + table + " WHERE " + key + " = '" + id + "'");
  }
}
