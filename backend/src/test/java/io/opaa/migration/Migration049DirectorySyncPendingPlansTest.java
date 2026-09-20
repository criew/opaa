package io.opaa.migration;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/049-create-directory-sync-pending-plans.yaml} (#1816, ADR-0036
 * Entscheidung 3): the plan a run above the threshold leaves behind, at most one per provider.
 */
class Migration049DirectorySyncPendingPlansTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/049-create-directory-sync-pending-plans.yaml";

  private static final UUID DEFAULT_ORGANIZATION =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

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
  void beforeTheChangesetThereIsNoPlaceForAPendingPlan() throws Exception {
    assertThat(tableExists("directory_sync_pending_plans")).isFalse();
  }

  /**
   * "Ein neuer Lauf ersetzt den ausstehenden Plan" is a decision of ADR-0036 and stands here as a
   * key: otherwise one would pile up every six hours and eventually the oldest would be confirmed.
   */
  @Test
  void aProviderCanHaveAtMostOnePendingPlan() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID provider = seedProvider("Haus A", "https://a.example");

    assertThatCode(() -> seedPlan(provider)).doesNotThrowAnyException();
    assertThatThrownBy(() -> seedPlan(provider))
        .hasMessageContaining("uk_directory_sync_pending_plans_provider");
  }

  @Test
  void twoProvidersEachCarryTheirOwnPendingPlan() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID first = seedProvider("Haus A", "https://a.example");
    UUID second = seedProvider("Haus B", "https://b.example");

    seedPlan(first);
    seedPlan(second);

    assertThat(planCount()).isEqualTo(2);
  }

  /** A plan without its provider has no meaning left; it goes with it, like the status line. */
  @Test
  void deletingAProviderTakesItsPendingPlanWithIt() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID provider = seedProvider("Haus A", "https://a.example");
    seedPlan(provider);

    execute("DELETE FROM oidc_providers WHERE id = ?", provider);

    assertThat(planCount()).isZero();
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

  private UUID seedProvider(String displayName, String issuer) throws SQLException {
    UUID provider = UUID.randomUUID();
    execute(
        "INSERT INTO oidc_providers (id, display_name, issuer_uri, client_id)"
            + " VALUES (?, ?, ?, ?)",
        provider,
        displayName,
        issuer,
        "opaa-frontend");
    return provider;
  }

  private void seedPlan(UUID providerId) throws SQLException {
    execute(
        "INSERT INTO directory_sync_pending_plans (id, organization_id, provider_id, created_at,"
            + " fingerprint, changed_fraction, memberships_removed, report)"
            + " VALUES (?, ?, ?, now(), ?, 0.67, 12, '{}')",
        UUID.randomUUID(),
        DEFAULT_ORGANIZATION,
        providerId,
        "abcdef");
  }

  private int planCount() throws SQLException {
    try (PreparedStatement statement =
            connection.prepareStatement("SELECT count(*) FROM directory_sync_pending_plans");
        ResultSet rows = statement.executeQuery()) {
      assertThat(rows.next()).isTrue();
      return rows.getInt(1);
    }
  }

  private void execute(String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.executeUpdate();
    }
  }

  private boolean tableExists(String table) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema()"
                + " AND table_name = ?")) {
      statement.setString(1, table);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }
}
