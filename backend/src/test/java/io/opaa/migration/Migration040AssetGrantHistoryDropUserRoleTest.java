package io.opaa.migration;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
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
 * Delta tests for {@code changes/040-asset-grant-history-drop-user-role.yaml} (#1811). Both
 * branches of the precondition are exercised: without a row carrying the value the constraint is
 * replaced, with one it stays - an installation with bestand from before #330 must keep starting
 * and keep its history readable.
 */
class Migration040AssetGrantHistoryDropUserRoleTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/040-asset-grant-history-drop-user-role.yaml";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    applyChangelog(connection, "db/changelog/changes/038-asset-grants-type-independent.yaml");
    applyChangelog(
        connection, "db/changelog/changes/039-asset-grant-history-type-independent.yaml");
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void beforeTheChangesetTheStrickenRoleIsStillAccepted() throws Exception {
    assertThat(roleCheckDefinition()).contains("'USER'::character varying");
  }

  @Test
  void theChangesetRemovesTheStrickenRoleFromTheCheck() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(roleCheckDefinition())
        .doesNotContain("'USER'::character varying")
        .contains("'VIEWER'::character varying")
        .contains("'OWNER'::character varying");
  }

  @Test
  void anIntervalWithTheStrickenRoleCanNoLongerBeWritten() throws Exception {
    Fixture fixture = seedLibrary();
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(() -> insertInterval(fixture, "USER"))
        .hasMessageContaining("chk_asset_grant_history_role");
    insertInterval(fixture, "VIEWER");
  }

  /**
   * The precondition's own branch: an installation still carrying a row from before #330 keeps the
   * old constraint, and the row stays readable.
   */
  @Test
  void anInstallationWithAnOldRowKeepsTheConstraintAndTheRow() throws Exception {
    Fixture fixture = seedLibrary();
    insertInterval(fixture, "USER");

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(roleCheckDefinition()).contains("'USER'::character varying");
    assertThat(count("SELECT count(*) FROM asset_grant_history WHERE role = 'USER'")).isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM databasechangelog WHERE id ="
                    + " '040-asset-grant-history-drop-user-role'"))
        .isEqualTo(1);
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

  private record Fixture(UUID organizationId, UUID userId, UUID libraryId) {}

  private Fixture seedLibrary() throws SQLException {
    UUID organization = UUID.randomUUID();
    execute(
        "INSERT INTO organizations (id, name) VALUES (?, ?)",
        organization,
        "Organisation " + organization);
    UUID user = UUID.randomUUID();
    execute(
        "INSERT INTO users (id, subject, issuer, organization_id) VALUES (?, ?, ?, ?)",
        user,
        "role-" + user,
        "https://issuer.example",
        organization);
    UUID library = UUID.randomUUID();
    execute(
        "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type, owner_user_id,"
            + " visibility, source_type) VALUES (?, ?, ?, 'USER', ?, 'PRIVATE', 'UPLOAD')",
        library,
        organization,
        "Bibliothek " + library,
        user);
    return new Fixture(organization, user, library);
  }

  private void insertInterval(Fixture fixture, String role) throws SQLException {
    execute(
        "INSERT INTO asset_grant_history (id, asset_type, asset_id, organization_id, subject_type,"
            + " subject_user_id, role, cause, valid_from, valid_to) VALUES (?,"
            + " 'KNOWLEDGE_LIBRARY', ?, ?, 'USER', ?, '"
            + role
            + "', 'GRANTED', now(), now())",
        UUID.randomUUID(),
        fixture.libraryId(),
        fixture.organizationId(),
        fixture.userId());
  }

  private String roleCheckDefinition() throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                + " WHERE conname = 'chk_asset_grant_history_role'")) {
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getString(1);
      }
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

  private long count(String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      assertThat(rows.next()).isTrue();
      return rows.getLong(1);
    }
  }
}
