package io.opaa.migration;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/060-create-permission-transfers.yaml} (#1834, ADR-0036
 * Entscheidung 10): the record of one carried-out transfer, the "Vorgangskennung" every history row
 * it writes points at.
 */
class Migration060PermissionTransfersTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/060-create-permission-transfers.yaml";

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
  void beforeTheChangesetThereIsNoTable() throws Exception {
    assertThat(tableCount()).isZero();
  }

  @Test
  void theTableCarriesOneTransfer() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID source = seedGroup("Referat 50");
    UUID target = seedGroup("Referat 52");

    UUID transfer = insertGroupTransfer(source, target, "ASSET_GRANTS,OWNERSHIP");

    assertThat(count("SELECT count(*) FROM permission_transfers WHERE id = '" + transfer + "'"))
        .isEqualTo(1);
    assertThat(
            stringOf("SELECT source_label FROM permission_transfers WHERE id = '" + transfer + "'"))
        .isEqualTo("Referat 50");
  }

  /**
   * The point of the operation: once the source holds nothing, it can be deleted - a RESTRICT key
   * from this table would make the transfer useless for a provider replacement (#1812's 409).
   */
  @Test
  void theSourceGroupStaysDeletableAfterTheTransferIsRecorded() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID source = seedGroup("Referat 50");
    UUID target = seedGroup("Referat 52");
    UUID transfer = insertGroupTransfer(source, target, "ASSET_GRANTS");

    execute("DELETE FROM groups WHERE id = ?", source);

    assertThat(count("SELECT count(*) FROM permission_transfers WHERE id = '" + transfer + "'"))
        .isEqualTo(1);
  }

  /** The name of a person as the source is never stored - ADR-0036, Entscheidung 10. */
  @Test
  void aPersonAsTheSourceCarriesNoLabel() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID person = seedUser("ausgeschieden@example.org");
    UUID successor = seedUser("nachfolge@example.org");

    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO permission_transfers (id, organization_id, source_type,"
                        + " source_user_id, source_label, target_type, target_user_id, scope,"
                        + " performed_at) VALUES (?, ?, 'USER', ?, 'Frau Vogt', 'USER', ?,"
                        + " 'OWNERSHIP', ?)",
                    UUID.randomUUID(),
                    DEFAULT_ORGANIZATION,
                    person,
                    successor,
                    java.sql.Timestamp.from(Instant.now())))
        .hasMessageContaining("chk_permission_transfers_source_label");
  }

  @Test
  void exactlyOneSubjectColumnIsSet() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID group = seedGroup("Referat 50");
    UUID person = seedUser("person@example.org");

    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO permission_transfers (id, organization_id, source_type,"
                        + " source_user_id, source_group_id, target_type, target_group_id, scope,"
                        + " performed_at) VALUES (?, ?, 'GROUP', ?, ?, 'GROUP', ?, 'OWNERSHIP', ?)",
                    UUID.randomUUID(),
                    DEFAULT_ORGANIZATION,
                    person,
                    group,
                    group,
                    java.sql.Timestamp.from(Instant.now())))
        .hasMessageContaining("chk_permission_transfers_source");
  }

  @Test
  void theScopeIsAListOfPartNames() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID source = seedGroup("Referat 50");
    UUID target = seedGroup("Referat 52");

    assertThatThrownBy(() -> insertGroupTransfer(source, target, "asset_grants"))
        .hasMessageContaining("chk_permission_transfers_scope");
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

  private UUID insertGroupTransfer(UUID source, UUID target, String scope) throws SQLException {
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO permission_transfers (id, organization_id, source_type, source_group_id,"
            + " source_label, target_type, target_group_id, target_label, scope, performed_at)"
            + " VALUES (?, ?, 'GROUP', ?, 'Referat 50', 'GROUP', ?, 'Referat 52', ?, ?)",
        id,
        DEFAULT_ORGANIZATION,
        source,
        target,
        scope,
        java.sql.Timestamp.from(Instant.now()));
    return id;
  }

  private UUID seedGroup(String name) throws SQLException {
    UUID group = UUID.randomUUID();
    execute(
        "INSERT INTO groups (id, organization_id, kind, name) VALUES (?, ?, 'AD_HOC', ?)",
        group,
        DEFAULT_ORGANIZATION,
        name);
    return group;
  }

  private UUID seedUser(String email) throws SQLException {
    UUID user = UUID.randomUUID();
    execute(
        "INSERT INTO users (id, subject, issuer, email, organization_id) VALUES (?, ?,"
            + " 'https://issuer.example.org', ?, ?)",
        user,
        user.toString(),
        email,
        DEFAULT_ORGANIZATION);
    return user;
  }

  private long tableCount() throws SQLException {
    return count(
        "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema()"
            + " AND table_name = 'permission_transfers'");
  }

  private void execute(String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      statement.executeUpdate();
    }
  }

  private long count(String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private String stringOf(String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getString(1);
    }
  }
}
