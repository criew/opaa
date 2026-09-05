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
 * Delta test for {@code changes/026-oidc-providers.yaml} (#1329, ADR-0025): the {@code
 * oidc_providers} table that replaces the single {@code OPAA_OIDC_*} issuer, its two invariants -
 * one row per issuer, at most one default provider - and the singleton seed marker that records the
 * one-time takeover of the environment configuration.
 */
class Migration026OidcProvidersTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH = "db/changelog/changes/026-oidc-providers.yaml";

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
  void createsTheProvidersTableWithItsClaimMappingDefaults() throws Exception {
    assertThat(tableExists("oidc_providers")).isFalse();
    assertThat(tableExists("oidc_provider_seed_marker")).isFalse();

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(tableExists("oidc_providers")).isTrue();
    assertThat(tableExists("oidc_provider_seed_marker")).isTrue();
    assertThat(columnNames("oidc_providers"))
        .containsExactlyInAnyOrder(
            "id",
            "display_name",
            "enabled",
            "is_default",
            "sort_order",
            "issuer_uri",
            "client_id",
            "jwk_set_uri",
            "email_claim",
            "display_name_claim",
            "roles_claim",
            "system_admin_role",
            "auditor_role",
            "groups_claim",
            "created_at",
            "updated_at");

    insertProvider("Verzeichnisdienst", "https://idp.example/realms/a", true, true);
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT email_claim, display_name_claim, roles_claim, groups_claim, sort_order"
                    + " FROM oidc_providers")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString("email_claim")).isEqualTo("email");
      assertThat(rs.getString("display_name_claim")).isEqualTo("name");
      assertThat(rs.getString("roles_claim")).isNull();
      assertThat(rs.getString("groups_claim")).isNull();
      assertThat(rs.getInt("sort_order")).isZero();
    }
  }

  @Test
  void rejectsASecondProviderWithTheSameIssuer() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    insertProvider("Beschäftigte", "https://idp.example/realms/a", true, true);

    assertThatThrownBy(() -> insertProvider("Partner", "https://idp.example/realms/a", true, false))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uq_oidc_providers_issuer_uri");
  }

  @Test
  void allowsAtMostOneDefaultProviderButAnyNumberOfNonDefaultOnes() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    insertProvider("Beschäftigte", "https://idp.example/realms/a", true, true);
    insertProvider("Partner", "https://idp.example/realms/b", true, false);
    insertProvider("Land", "https://idp.example/realms/c", false, false);

    assertThatThrownBy(
            () -> insertProvider("Zweiter Standard", "https://idp.example/realms/d", true, true))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ux_oidc_providers_single_default");
  }

  @Test
  void theSeedMarkerIsASingleton() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    try (Statement statement = connection.createStatement()) {
      statement.execute("INSERT INTO oidc_provider_seed_marker (id, seeded_at) VALUES (1, now())");
    }

    assertThatThrownBy(
            () -> {
              try (Statement statement = connection.createStatement()) {
                statement.execute(
                    "INSERT INTO oidc_provider_seed_marker (id, seeded_at) VALUES (2, now())");
              }
            })
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_oidc_provider_seed_marker_singleton");
  }

  private void insertProvider(String name, String issuer, boolean enabled, boolean isDefault)
      throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO oidc_providers (id, display_name, enabled, is_default, issuer_uri,"
              + " client_id) VALUES (gen_random_uuid(), '"
              + name
              + "', "
              + enabled
              + ", "
              + isDefault
              + ", '"
              + issuer
              + "', 'opaa-frontend')");
    }
  }

  private boolean tableExists(String table) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND"
                    + " table_name = '"
                    + table
                    + "'")) {
      return rs.next();
    }
  }

  private List<String> columnNames(String table) throws SQLException {
    List<String> names = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public'"
                    + " AND table_name = '"
                    + table
                    + "'")) {
      while (rs.next()) {
        names.add(rs.getString("column_name"));
      }
    }
    return names;
  }
}
