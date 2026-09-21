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
 * Delta tests for {@code changes/062-permission-history-transfer.yaml} (#1834, ADR-0036
 * Entscheidung 10): the shared operation id on the four history tables, and the two causes that
 * name the two sides of a transfer.
 */
class Migration062PermissionHistoryTransferTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/062-permission-history-transfer.yaml";

  private static final String[] PREREQUISITES = {
    "db/changelog/changes/041-groups-provider-origin.yaml",
    "db/changelog/changes/042-create-capability-grants.yaml",
    "db/changelog/changes/043-space-memberships-subject.yaml",
    "db/changelog/changes/044-create-space-membership-history.yaml",
    "db/changelog/changes/050-create-capability-grant-history.yaml",
    "db/changelog/changes/052-create-asset-ownership-history.yaml",
    "db/changelog/changes/060-create-permission-transfers.yaml",
  };

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
  void beforeTheChangesetNoHistoryTableCarriesTheOperationId() throws Exception {
    applyPrerequisites();

    assertThat(transferColumns()).isZero();
  }

  @Test
  void allFourHistoryTablesCarryTheOperationId() throws Exception {
    applyAll();

    assertThat(transferColumns()).isEqualTo(4);
  }

  /** Both sides of one transfer, on one asset, with one instant and one operation id. */
  @Test
  void bothSidesOfAGrantTransferAreAccepted() throws Exception {
    applyAll();
    UUID transfer = seedTransfer();
    UUID asset = UUID.randomUUID();
    UUID sourceGroup = seedGroup("Referat 50");
    UUID targetGroup = seedGroup("Referat 52");
    Instant at = Instant.now();

    insertGrantInterval(asset, sourceGroup, "TRANSFERRED_OUT", transfer, at, at);
    insertGrantInterval(asset, targetGroup, "TRANSFERRED_IN", transfer, at, null);

    assertThat(
            count(
                "SELECT count(*) FROM asset_grant_history WHERE transfer_id = '" + transfer + "'"))
        .isEqualTo(2);
  }

  @Test
  void theOldCausesStillPass() throws Exception {
    applyAll();
    UUID asset = UUID.randomUUID();
    UUID group = seedGroup("Referat 50");

    insertGrantInterval(asset, group, "GRANTED", null, Instant.now(), null);

    assertThat(count("SELECT count(*) FROM asset_grant_history")).isEqualTo(1);
  }

  @Test
  void anUnknownCauseIsStillRefused() throws Exception {
    applyAll();
    UUID group = seedGroup("Referat 50");

    assertThatThrownBy(
            () ->
                insertGrantInterval(
                    UUID.randomUUID(), group, "HANDED_OVER", null, Instant.now(), null))
        .hasMessageContaining("chk_asset_grant_history_cause");
  }

  /** The interval is the Auskunft and must survive the record that grouped it. */
  @Test
  void aDeletedTransferLeavesItsIntervalsBehind() throws Exception {
    applyAll();
    UUID transfer = seedTransfer();
    UUID asset = UUID.randomUUID();
    UUID group = seedGroup("Referat 52");
    insertGrantInterval(asset, group, "TRANSFERRED_IN", transfer, Instant.now(), null);

    execute("DELETE FROM permission_transfers WHERE id = ?", transfer);

    assertThat(count("SELECT count(*) FROM asset_grant_history WHERE transfer_id IS NULL"))
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

  private void applyPrerequisites() throws Exception {
    for (String path : PREREQUISITES) {
      applyChangelog(connection, path);
    }
  }

  private void applyAll() throws Exception {
    applyPrerequisites();
    applyChangelog(connection, CHANGELOG_PATH);
  }

  private long transferColumns() throws SQLException {
    return count(
        "SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema()"
            + " AND column_name = 'transfer_id' AND table_name IN ('asset_grant_history',"
            + " 'space_membership_history', 'capability_grant_history',"
            + " 'asset_ownership_history')");
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

  private UUID seedTransfer() throws SQLException {
    UUID source = seedGroup("Quelle");
    UUID target = seedGroup("Ziel");
    UUID transfer = UUID.randomUUID();
    execute(
        "INSERT INTO permission_transfers (id, organization_id, source_type, source_group_id,"
            + " target_type, target_group_id, scope, performed_at) VALUES (?, ?, 'GROUP', ?,"
            + " 'GROUP', ?, 'ASSET_GRANTS', ?)",
        transfer,
        DEFAULT_ORGANIZATION,
        source,
        target,
        java.sql.Timestamp.from(Instant.now()));
    return transfer;
  }

  private void insertGrantInterval(
      UUID assetId, UUID groupId, String cause, UUID transferId, Instant validFrom, Instant validTo)
      throws SQLException {
    execute(
        "INSERT INTO asset_grant_history (id, asset_type, asset_id, organization_id, subject_type,"
            + " subject_group_id, role, cause, valid_from, valid_to, transfer_id) VALUES (?,"
            + " 'KNOWLEDGE_LIBRARY', ?, ?, 'GROUP', ?, 'VIEWER', ?, ?, ?, ?)",
        UUID.randomUUID(),
        assetId,
        DEFAULT_ORGANIZATION,
        groupId,
        cause,
        java.sql.Timestamp.from(validFrom),
        validTo == null ? null : java.sql.Timestamp.from(validTo),
        transferId);
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
}
