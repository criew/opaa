package io.opaa.migration;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

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
 * Delta tests for {@code changes/065-directory-sync-pending-plans-accounts-locked.yaml} (#1818,
 * ADR-0036 Entscheidung 3): how many accounts a pending plan would lock - the second number that
 * makes it loud.
 */
class Migration065PendingPlanAccountsLockedTest extends AbstractMigrationTest {

  private static final String PENDING_PLANS_PATH =
      "db/changelog/changes/049-create-directory-sync-pending-plans.yaml";

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/065-directory-sync-pending-plans-accounts-locked.yaml";

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
    applyChangelog(connection, PENDING_PLANS_PATH);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void beforeTheChangesetAPendingPlanCountsOnlyMemberships() throws Exception {
    assertThat(columnExists("directory_sync_pending_plans", "accounts_locked")).isFalse();
  }

  @Test
  void anExistingPendingPlanLocksNobody() throws Exception {
    UUID plan = seedPendingPlan();

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(columnExists("directory_sync_pending_plans", "accounts_locked")).isTrue();
    assertThat(
            count(
                "SELECT count(*) FROM directory_sync_pending_plans WHERE id = '"
                    + plan
                    + "' AND accounts_locked = 0"))
        .as("a plan that predates the column was computed without account locks")
        .isEqualTo(1);
  }

  @Test
  void theChangelogIsReferencedByTheMasterChangelog() throws Exception {
    assertThat(masterChangelog()).contains(CHANGELOG_PATH);
  }

  // -----------------------------------------------------------------------------------------

  private UUID seedPendingPlan() throws SQLException {
    UUID organization = DEFAULT_ORGANIZATION;
    UUID provider = UUID.randomUUID();
    execute(
        "INSERT INTO oidc_providers (id, display_name, issuer_uri, client_id)"
            + " VALUES (?, ?, ?, ?)",
        provider,
        "Haus A",
        "https://idp.example/realms/" + provider,
        "opaa-frontend");
    UUID plan = UUID.randomUUID();
    execute(
        "INSERT INTO directory_sync_pending_plans (id, organization_id, provider_id, created_at,"
            + " fingerprint, changed_fraction, memberships_removed, report)"
            + " VALUES (?, ?, ?, now(), 'fingerprint', 0.67, 12, '{}')",
        plan,
        organization,
        provider);
    return plan;
  }

  private void execute(String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.executeUpdate();
    }
  }

  private long count(String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      assertThat(rows.next()).isTrue();
      return rows.getLong(1);
    }
  }

  private boolean columnExists(String table, String column) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM information_schema.columns WHERE table_schema = current_schema()"
                + " AND table_name = ? AND column_name = ?")) {
      statement.setString(1, table);
      statement.setString(2, column);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }

  private String masterChangelog() throws Exception {
    return new String(
        requireNonNull(
                getClass()
                    .getClassLoader()
                    .getResourceAsStream("db/changelog/db.changelog-master.yaml"))
            .readAllBytes(),
        StandardCharsets.UTF_8);
  }
}
