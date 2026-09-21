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
 * Delta tests for {@code changes/061-create-permission-transfer-objects.yaml} (#1834, ADR-0036
 * Entscheidung 10): which objects a transfer touched, so the sharing view of an object can name the
 * operation without a union over the history tables.
 */
class Migration061PermissionTransferObjectsTest extends AbstractMigrationTest {

  private static final String TRANSFERS_PATH =
      "db/changelog/changes/060-create-permission-transfers.yaml";
  private static final String CHANGELOG_PATH =
      "db/changelog/changes/061-create-permission-transfer-objects.yaml";

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
    applyChangelog(connection, TRANSFERS_PATH);

    assertThat(tableCount()).isZero();
  }

  @Test
  void anObjectIsRecordedOncePerTransfer() throws Exception {
    applyAll();
    UUID transfer = seedTransfer();
    UUID asset = UUID.randomUUID();

    insertObject(transfer, "KNOWLEDGE_LIBRARY", asset);

    assertThatThrownBy(() -> insertObject(transfer, "KNOWLEDGE_LIBRARY", asset))
        .hasMessageContaining("uk_permission_transfer_objects");
  }

  /** The object column carries no foreign key (ADR-0016) - the record outlives the object. */
  @Test
  void anAssetIdNeedsNoExistingObject() throws Exception {
    applyAll();
    UUID transfer = seedTransfer();

    insertObject(transfer, "SPACE", UUID.randomUUID());

    assertThat(count("SELECT count(*) FROM permission_transfer_objects")).isEqualTo(1);
  }

  /** The rows are parts of the operation, not operations of their own. */
  @Test
  void theRowsGoWithTheirTransfer() throws Exception {
    applyAll();
    UUID transfer = seedTransfer();
    insertObject(transfer, "KNOWLEDGE_LIBRARY", UUID.randomUUID());

    execute("DELETE FROM permission_transfers WHERE id = ?", transfer);

    assertThat(count("SELECT count(*) FROM permission_transfer_objects")).isZero();
  }

  @Test
  void theAssetTypeIsCheckedForShape() throws Exception {
    applyAll();
    UUID transfer = seedTransfer();

    assertThatThrownBy(() -> insertObject(transfer, "knowledge library", UUID.randomUUID()))
        .hasMessageContaining("chk_permission_transfer_objects_asset_type_format");
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

  private void applyAll() throws Exception {
    applyChangelog(connection, TRANSFERS_PATH);
    applyChangelog(connection, CHANGELOG_PATH);
  }

  private UUID seedTransfer() throws SQLException {
    UUID group = UUID.randomUUID();
    execute(
        "INSERT INTO groups (id, organization_id, kind, name) VALUES (?, ?, 'AD_HOC', 'Referat"
            + " 50')",
        group,
        DEFAULT_ORGANIZATION);
    UUID target = UUID.randomUUID();
    execute(
        "INSERT INTO groups (id, organization_id, kind, name) VALUES (?, ?, 'AD_HOC', 'Referat"
            + " 52')",
        target,
        DEFAULT_ORGANIZATION);
    UUID transfer = UUID.randomUUID();
    execute(
        "INSERT INTO permission_transfers (id, organization_id, source_type, source_group_id,"
            + " target_type, target_group_id, scope, performed_at) VALUES (?, ?, 'GROUP', ?,"
            + " 'GROUP', ?, 'ASSET_GRANTS', ?)",
        transfer,
        DEFAULT_ORGANIZATION,
        group,
        target,
        java.sql.Timestamp.from(Instant.now()));
    return transfer;
  }

  private void insertObject(UUID transfer, String assetType, UUID assetId) throws SQLException {
    execute(
        "INSERT INTO permission_transfer_objects (id, transfer_id, organization_id, asset_type,"
            + " asset_id) VALUES (?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        transfer,
        DEFAULT_ORGANIZATION,
        assetType,
        assetId);
  }

  private long tableCount() throws SQLException {
    return count(
        "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema()"
            + " AND table_name = 'permission_transfer_objects'");
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
