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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/003-diagnostic-impersonation-grant-delete-rules.yaml} (#1509):
 * every foreign key of {@code diagnostic_impersonation_grants} onto an account or onto its scope
 * group cascades, so deleting one of the accounts involved takes the Befugnis with it instead of
 * being blocked by it. Against the state {@code db/changelog/changes/001-baseline.yaml} leaves
 * behind, where only {@code holder_user_id} cascaded.
 *
 * <p>{@code organization_id} deliberately keeps {@code RESTRICT}: the tenant root is never deleted.
 */
class Migration003DiagnosticGrantDeleteRulesTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/003-diagnostic-impersonation-grant-delete-rules.yaml";

  private static final String HOLDER_KEY = "fk_diagnostic_impersonation_grants_holder_organization";
  private static final String GRANTER_KEY =
      "fk_diagnostic_impersonation_grants_granter_organization";
  private static final String REVOKER_KEY =
      "fk_diagnostic_impersonation_grants_revoker_organization";
  private static final String SCOPE_KEY = "fk_diagnostic_impersonation_grants_scope_organization";
  private static final String ORGANIZATION_KEY = "fk_diagnostic_impersonation_grants_organization";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws SQLException {
    connection = connect();
    connection.setAutoCommit(true);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  /** The asymmetry this changeset resolves: four of the five keys refuse the deletion. */
  @Test
  void beforeTheMigrationOnlyTheHolderCascades() throws Exception {
    assertThat(deleteRule(HOLDER_KEY)).isEqualTo("c");
    assertThat(deleteRule(GRANTER_KEY)).isEqualTo("r");
    assertThat(deleteRule(REVOKER_KEY)).isEqualTo("r");
    assertThat(deleteRule(SCOPE_KEY)).isEqualTo("r");

    Fixture fixture = seedGrant();

    assertThatThrownBy(() -> deleteUser(fixture.granter()))
        .as("the account that issued the Befugnis cannot be deleted while the row exists")
        .hasMessageContaining(GRANTER_KEY);
  }

  @Test
  void afterTheMigrationEveryAccountAndScopeKeyCascadesAndTheOrganizationDoesNot()
      throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(deleteRule(HOLDER_KEY)).isEqualTo("c");
    assertThat(deleteRule(GRANTER_KEY)).isEqualTo("c");
    assertThat(deleteRule(REVOKER_KEY)).isEqualTo("c");
    assertThat(deleteRule(SCOPE_KEY)).isEqualTo("c");
    assertThat(deleteRule(ORGANIZATION_KEY))
        .as("the tenant root is never deleted and keeps RESTRICT")
        .isEqualTo("r");
  }

  @Test
  void deletingTheHolderAccountTakesTheBefugnisWithIt() throws Exception {
    Fixture fixture = seedGrant();
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatCode(() -> deleteUser(fixture.holder())).doesNotThrowAnyException();

    assertThat(grantExists(fixture.grantId())).isFalse();
  }

  @Test
  void deletingTheGrantingAccountTakesTheBefugnisWithIt() throws Exception {
    Fixture fixture = seedGrant();
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatCode(() -> deleteUser(fixture.granter())).doesNotThrowAnyException();

    assertThat(grantExists(fixture.grantId())).isFalse();
  }

  @Test
  void deletingTheRevokingAccountTakesTheRevokedBefugnisWithIt() throws Exception {
    Fixture fixture = seedGrant();
    revokeGrant(fixture.grantId(), fixture.revoker());
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatCode(() -> deleteUser(fixture.revoker())).doesNotThrowAnyException();

    assertThat(grantExists(fixture.grantId()))
        .as("a revoked Befugnis must not block the deletion of the account that revoked it")
        .isFalse();
  }

  /**
   * Standing guard, not a property of the changeset: every test here applies {@link
   * #CHANGELOG_PATH} itself, so a changelog file missing from {@code db.changelog-master.yaml}
   * would still pass every assertion above while no installation ever ran it - and no other test
   * judges these delete rules against the delivered changelog.
   */
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

  /**
   * Database behaviour only: a scope must be an ORG_UNIT group, and {@code
   * GroupService#deleteGroup} rejects those outright - so this rule has no reachable caller until a
   * deletion path for Organisationseinheiten exists.
   */
  @Test
  void deletingTheScopeGroupTakesTheBefugnisWithIt() throws Exception {
    Fixture fixture = seedGrant();
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatCode(() -> deleteGroup(fixture.scopeGroup())).doesNotThrowAnyException();

    assertThat(grantExists(fixture.grantId()))
        .as("scope_group_id is NOT NULL, so a Befugnis without its scope group cannot exist")
        .isFalse();
  }

  // -----------------------------------------------------------------------------------------
  // Fixture
  // -----------------------------------------------------------------------------------------

  private record Fixture(
      UUID organization, UUID holder, UUID granter, UUID revoker, UUID scopeGroup, UUID grantId) {}

  /** One live Befugnis with a distinct account per person column, all in the same organization. */
  private Fixture seedGrant() throws SQLException {
    UUID organization = insertOrganization();
    UUID holder = insertUser(organization, "holder");
    UUID granter = insertUser(organization, "granter");
    UUID revoker = insertUser(organization, "revoker");
    UUID scopeGroup = insertGroup(organization);
    UUID grantId = UUID.randomUUID();

    Instant validFrom = Instant.parse("2026-09-01T08:00:00Z");
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO diagnostic_impersonation_grants (id, organization_id, holder_user_id,"
                + " scope_group_id, valid_from, valid_until, granted_by_user_id, granted_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
      statement.setObject(1, grantId);
      statement.setObject(2, organization);
      statement.setObject(3, holder);
      statement.setObject(4, scopeGroup);
      statement.setObject(5, validFrom.atOffset(ZoneOffset.UTC));
      statement.setObject(6, validFrom.plus(30, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC));
      statement.setObject(7, granter);
      statement.setObject(8, validFrom.atOffset(ZoneOffset.UTC));
      statement.executeUpdate();
    }
    return new Fixture(organization, holder, granter, revoker, scopeGroup, grantId);
  }

  private void revokeGrant(UUID grantId, UUID revoker) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE diagnostic_impersonation_grants SET revoked_at = ?, revoked_by_user_id = ?"
                + " WHERE id = ?")) {
      statement.setObject(1, Instant.parse("2026-09-05T08:00:00Z").atOffset(ZoneOffset.UTC));
      statement.setObject(2, revoker);
      statement.setObject(3, grantId);
      statement.executeUpdate();
    }
  }

  private UUID insertOrganization() throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement("INSERT INTO organizations (id, name) VALUES (?, ?)")) {
      statement.setObject(1, id);
      statement.setString(2, "Organisation " + id);
      statement.executeUpdate();
    }
    return id;
  }

  private UUID insertUser(UUID organizationId, String role) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO users (id, subject, issuer, organization_id) VALUES (?, ?, ?, ?)")) {
      statement.setObject(1, id);
      statement.setString(2, role + "-" + id);
      statement.setString(3, "https://issuer.example");
      statement.setObject(4, organizationId);
      statement.executeUpdate();
    }
    return id;
  }

  private UUID insertGroup(UUID organizationId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO groups (id, organization_id, kind, name) VALUES (?, ?, 'ORG_UNIT', ?)")) {
      statement.setObject(1, id);
      statement.setObject(2, organizationId);
      statement.setString(3, "Amt für Personal " + id);
      statement.executeUpdate();
    }
    return id;
  }

  private void deleteUser(UUID id) throws SQLException {
    executeDelete("DELETE FROM users WHERE id = ?", id);
  }

  private void deleteGroup(UUID id) throws SQLException {
    executeDelete("DELETE FROM groups WHERE id = ?", id);
  }

  private void executeDelete(String sql, UUID id) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      statement.executeUpdate();
    }
  }

  private boolean grantExists(UUID grantId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT 1 FROM diagnostic_impersonation_grants WHERE id = ?")) {
      statement.setObject(1, grantId);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next();
      }
    }
  }

  /** {@code confdeltype} of the named foreign key - {@code c} = CASCADE, {@code r} = RESTRICT. */
  private String deleteRule(String constraintName) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT confdeltype FROM pg_constraint WHERE conname = ? AND contype = 'f'")) {
      statement.setString(1, constraintName);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).as("foreign key %s must exist", constraintName).isTrue();
        return rs.getString(1);
      }
    }
  }
}
